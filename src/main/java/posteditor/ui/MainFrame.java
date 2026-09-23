package posteditor.ui;

import posteditor.Config;
import posteditor.git.GitService;
import posteditor.markdown.MarkdownRenderer;
import posteditor.model.Post;
import posteditor.model.PostRepository;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Janela principal: lista de posts, formulário do front matter e editor.
 */
public class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final String APP_TITLE = "Post Editor — GitHub Pages";

    private final Config config;
    private PostRepository repository;
    private GitService git;

    private Post current;
    private boolean dirty;
    private boolean loading;
    private boolean busy;
    private boolean ignoreSelection;
    /** Imagens copiadas para o repositório que ainda não foram publicadas. */
    private final List<String> pendingImages = new ArrayList<String>();
    private List<Post> allPosts = new ArrayList<Post>();

    private final DefaultListModel<Post> listModel = new DefaultListModel<Post>();
    private final JList<Post> postList = new JList<Post>(listModel);
    private final JTextField filterField = new JTextField();
    private final JTextField titleField = new JTextField();
    private final JTextField dateField = new JTextField();
    private final JTextField layoutField = new JTextField();
    private final JTextField categoriesField = new JTextField();
    private final JTextField tagsField = new JTextField();
    private final MarkdownEditorPanel editor = new MarkdownEditorPanel();
    private final JTextArea logArea = new JTextArea();
    private final JLabel repoLabel = new JLabel("Nenhum repositório selecionado");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel fileLabel = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private final Action openRepoAction = action("Abrir repositório...", "Selecionar a pasta local do repositório do blog",
            new Runnable() {
                public void run() {
                    chooseRepository();
                }
            });
    private final Action syncAction = action("Sincronizar", "Baixar alterações do GitHub (git pull)",
            new Runnable() {
                public void run() {
                    synchronize();
                }
            });
    private final Action newAction = action("Novo post", "Criar um novo post (Ctrl+N)", new Runnable() {
        public void run() {
            newPost();
        }
    });
    private final Action publishAction = action("Salvar e publicar", "Salvar, fazer commit e push (Ctrl+S)",
            new Runnable() {
                public void run() {
                    publish();
                }
            });
    private final Action deleteAction = action("Excluir post", "Excluir o post, fazer commit e push", new Runnable() {
        public void run() {
            deletePost();
        }
    });

    public MainFrame(Config config) {
        super(APP_TITLE);
        this.config = config;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (busy) {
                    JOptionPane.showMessageDialog(MainFrame.this, "Aguarde a operação do git terminar.");
                    return;
                }
                if (confirmDiscard()) {
                    dispose();
                    System.exit(0);
                }
            }
        });

        buildUi();
        installShortcuts();
        setPostEditingEnabled(false);
        updateActions();

        setSize(new Dimension(1300, 820));
        setLocationRelativeTo(null);

        final File saved = config.getRepoPath();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (saved != null && saved.isDirectory()) {
                    openRepository(saved);
                } else {
                    setStatus("Clique em \"Abrir repositório...\" e selecione a pasta local (clonada) do seu blog.");
                }
            }
        });
    }

    // --------------------------------------------------------------------- UI

    private void buildUi() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(openRepoAction);
        toolbar.add(syncAction);
        toolbar.addSeparator();
        toolbar.add(newAction);
        toolbar.add(publishAction);
        toolbar.add(deleteAction);
        toolbar.addSeparator();
        repoLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        toolbar.add(repoLabel);

        // Lista de posts
        postList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        postList.setCellRenderer(new PostCellRenderer());
        postList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting() && !ignoreSelection) {
                    onPostSelected();
                }
            }
        });
        filterField.setToolTipText("Filtrar posts por título ou arquivo");
        filterField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            void changed() {
                applyFilter();
            }
        });
        JPanel filterPanel = new JPanel(new BorderLayout(4, 0));
        filterPanel.add(new JLabel("Buscar:"), BorderLayout.WEST);
        filterPanel.add(filterField, BorderLayout.CENTER);
        filterPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel left = new JPanel(new BorderLayout());
        left.add(filterPanel, BorderLayout.NORTH);
        left.add(new JScrollPane(postList), BorderLayout.CENTER);
        left.setBorder(BorderFactory.createTitledBorder("Posts"));
        left.setPreferredSize(new Dimension(290, 400));

        // Formulário do front matter
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Informações do post"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        addField(form, c, 0, 0, "Título:", titleField, 5);
        addField(form, c, 1, 0, "Data:", dateField, 1);
        addField(form, c, 1, 2, "Layout:", layoutField, 1);
        addField(form, c, 1, 4, "Arquivo:", fileLabel, 1);
        addField(form, c, 2, 0, "Categorias:", categoriesField, 1);
        addField(form, c, 2, 2, "Tags:", tagsField, 3);
        dateField.setToolTipText("Formato: AAAA-MM-DD HH:MM:SS -0300");
        categoriesField.setToolTipText("Separe por vírgulas");
        tagsField.setToolTipText("Separe por vírgulas");
        titleField.setFont(titleField.getFont().deriveFont(Font.BOLD, titleField.getFont().getSize() + 2f));
        fileLabel.setForeground(java.awt.Color.GRAY);

        SimpleDocumentListener dirtyListener = new SimpleDocumentListener() {
            @Override
            void changed() {
                markDirty();
            }
        };
        titleField.getDocument().addDocumentListener(dirtyListener);
        dateField.getDocument().addDocumentListener(dirtyListener);
        layoutField.getDocument().addDocumentListener(dirtyListener);
        categoriesField.getDocument().addDocumentListener(dirtyListener);
        tagsField.getDocument().addDocumentListener(dirtyListener);
        editor.addDocumentListener(dirtyListener);
        editor.setImageImporter(new MarkdownEditorPanel.ImageImporter() {
            @Override
            public String importImage(File file) throws IOException {
                return importImageIntoRepo(file);
            }
        });

        JPanel right = new JPanel(new BorderLayout());
        right.add(form, BorderLayout.NORTH);
        right.add(editor, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        mainSplit.setDividerLocation(290);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log do git"));
        logScroll.setPreferredSize(new Dimension(400, 130));

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplit, logScroll);
        vertical.setResizeWeight(0.85);

        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(160, 16));
        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(progress, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(vertical, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
    }

    private static void addField(JPanel panel, GridBagConstraints c, int row, int col, String label,
                                 Component field, int span) {
        c.gridy = row;
        c.gridx = col;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = col + 1;
        c.gridwidth = span;
        c.weightx = 1;
        panel.add(field, c);
        c.gridwidth = 1;
    }

    private void installShortcuts() {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('S', menu), "publish");
        root.getActionMap().put("publish", publishAction);
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('N', menu), "new");
        root.getActionMap().put("new", newAction);
    }

    private static Action action(String name, String tooltip, final Runnable body) {
        AbstractAction a = new AbstractAction(name) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (isEnabled()) {
                    body.run();
                }
            }
        };
        a.putValue(Action.SHORT_DESCRIPTION, tooltip);
        return a;
    }

    // ------------------------------------------------------------ repositório

    private void chooseRepository() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser chooser = new JFileChooser(config.getRepoPath());
        chooser.setDialogTitle("Selecione a pasta do repositório do blog (GitHub Pages)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openRepository(chooser.getSelectedFile());
        }
    }

    private void openRepository(final File dir) {
        final GitService candidate = new GitService(dir, new GitService.Listener() {
            @Override
            public void onOutput(String text) {
                appendLog(text);
            }
        });
        runInBackground("Abrindo repositório...", new Task<String>() {
            @Override
            public String call() throws Exception {
                if (!candidate.isRepository()) {
                    throw new IOException("A pasta selecionada não é um repositório git:\n" + dir
                            + "\n\nClone o repositório do blog antes (git clone ...) e selecione a pasta clonada.");
                }
                String warning = null;
                try {
                    candidate.pull();
                } catch (IOException e) {
                    warning = e.getMessage();
                }
                return warning;
            }

            @Override
            public void done(String warning) {
                git = candidate;
                repository = new PostRepository(dir, config.getPostsDir(), config.getImagesDir());
                config.setRepoPath(dir);
                config.save();
                editor.setRenderer(new MarkdownRenderer(dir));
                repoLabel.setText("Repositório: " + dir.getName());
                repoLabel.setToolTipText(dir.getAbsolutePath());
                setTitle(APP_TITLE + " — " + dir.getName());
                clearEditor();
                reloadPosts(null);
                if (warning != null) {
                    appendLog("Aviso: não foi possível sincronizar (git pull).");
                    setStatus("Repositório aberto, mas a sincronização falhou. Veja o log.");
                }
            }
        });
    }

    private void synchronize() {
        if (!confirmDiscard()) {
            return;
        }
        final File selected = current == null ? null : current.getFile();
        runInBackground("Sincronizando com o GitHub...", new Task<Void>() {
            @Override
            public Void call() throws Exception {
                git.pull();
                return null;
            }

            @Override
            public void done(Void result) {
                reloadPosts(selected);
                setStatus("Repositório sincronizado.");
            }
        });
    }

    private void reloadPosts(File select) {
        try {
            allPosts = repository.listPosts();
        } catch (IOException e) {
            allPosts = new ArrayList<Post>();
            showError("Erro ao ler os posts", e);
        }
        applyFilter();
        if (!repository.getPostsFolder().isDirectory()) {
            setStatus("A pasta " + config.getPostsDir() + " ainda não existe; ela será criada ao publicar o primeiro post.");
        } else {
            setStatus(allPosts.size() + " post(s) encontrados.");
        }
        if (select != null) {
            for (int i = 0; i < listModel.size(); i++) {
                File f = listModel.get(i).getFile();
                if (f != null && f.equals(select)) {
                    ignoreSelection = true;
                    postList.setSelectedIndex(i);
                    postList.ensureIndexIsVisible(i);
                    ignoreSelection = false;
                    loadPost(listModel.get(i));
                    break;
                }
            }
        }
        updateActions();
    }

    private void applyFilter() {
        String q = filterField.getText().trim().toLowerCase(Locale.ROOT);
        File selected = current == null ? null : current.getFile();
        ignoreSelection = true;
        try {
            listModel.clear();
            for (Post p : allPosts) {
                String hay = (p.getTitle() + " " + p.getFile().getName()).toLowerCase(Locale.ROOT);
                if (q.isEmpty() || hay.contains(q)) {
                    listModel.addElement(p);
                    if (selected != null && selected.equals(p.getFile())) {
                        postList.setSelectedIndex(listModel.size() - 1);
                    }
                }
            }
        } finally {
            ignoreSelection = false;
        }
    }

    // ------------------------------------------------------------------ posts

    private void onPostSelected() {
        Post selected = postList.getSelectedValue();
        if (selected == null || (current != null && selected.getFile() != null
                && selected.getFile().equals(current.getFile()))) {
            return;
        }
        if (!confirmDiscard()) {
            // Volta a seleção para o post atual
            ignoreSelection = true;
            if (current != null && current.getFile() != null) {
                for (int i = 0; i < listModel.size(); i++) {
                    if (current.getFile().equals(listModel.get(i).getFile())) {
                        postList.setSelectedIndex(i);
                    }
                }
            } else {
                postList.clearSelection();
            }
            ignoreSelection = false;
            return;
        }
        try {
            // Relê do disco para pegar a versão mais recente
            loadPost(repository.load(selected.getFile()));
        } catch (IOException e) {
            showError("Erro ao abrir o post", e);
        }
    }

    private void loadPost(Post post) {
        loading = true;
        try {
            current = post;
            titleField.setText(post.getTitle());
            dateField.setText(post.getDate());
            layoutField.setText(post.getLayout());
            categoriesField.setText(Post.joinCommaList(post.getCategories()));
            tagsField.setText(Post.joinCommaList(post.getTags()));
            fileLabel.setText(post.getFile() == null ? "(será criado ao publicar)"
                    : repository.relativize(post.getFile()));
            editor.setText(post.getBody());
            pendingImages.clear();
            dirty = false;
            setPostEditingEnabled(true);
        } finally {
            loading = false;
        }
        updateActions();
    }

    private void clearEditor() {
        loading = true;
        try {
            current = null;
            titleField.setText("");
            dateField.setText("");
            layoutField.setText("");
            categoriesField.setText("");
            tagsField.setText("");
            fileLabel.setText(" ");
            editor.setText("");
            pendingImages.clear();
            dirty = false;
            setPostEditingEnabled(false);
        } finally {
            loading = false;
        }
        updateActions();
    }

    private void newPost() {
        if (!confirmDiscard()) {
            return;
        }
        Post post = new Post();
        post.setLayout(config.getDefaultLayout());
        post.setDate(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date()));
        ignoreSelection = true;
        postList.clearSelection();
        ignoreSelection = false;
        loadPost(post);
        titleField.requestFocusInWindow();
        setStatus("Novo post: preencha o título e escreva o conteúdo. Depois clique em \"Salvar e publicar\".");
    }

    private boolean fillPostFromForm() {
        String title = titleField.getText().trim();
        String date = dateField.getText().trim();
        if (title.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Informe o título do post.", "Título obrigatório",
                    JOptionPane.WARNING_MESSAGE);
            titleField.requestFocusInWindow();
            return false;
        }
        if (!date.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
            JOptionPane.showMessageDialog(this, "A data deve começar com AAAA-MM-DD (ex.: 2024-05-10 14:30:00 -0300).",
                    "Data inválida", JOptionPane.WARNING_MESSAGE);
            dateField.requestFocusInWindow();
            return false;
        }
        current.setTitle(title);
        current.setDate(date);
        current.setLayout(layoutField.getText().trim());
        current.setCategories(Post.splitCommaList(categoriesField.getText()));
        current.setTags(Post.splitCommaList(tagsField.getText()));
        current.setBody(editor.getText());
        return true;
    }

    private void publish() {
        if (current == null || repository == null || !fillPostFromForm()) {
            return;
        }
        final File file;
        try {
            file = repository.save(current);
        } catch (IOException e) {
            showError("Erro ao salvar o arquivo", e);
            return;
        }
        final String postPath = repository.relativize(file);
        final List<String> paths = new ArrayList<String>();
        paths.add(postPath);
        for (String img : pendingImages) {
            if (new File(repository.getRoot(), img).exists()) {
                paths.add(img);
            }
        }
        final String title = current.getTitle();
        loading = true;
        fileLabel.setText(postPath);
        loading = false;

        runInBackground("Publicando no GitHub...", new Task<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                boolean isNew = !git.isTracked(postPath);
                String message = (isNew ? "Novo post: " : "Atualiza post: ") + title;
                return git.commitAndPush(paths, false, message);
            }

            @Override
            public void done(Boolean committed) {
                dirty = false;
                pendingImages.clear();
                reloadPosts(file);
                setStatus(committed ? "Post \"" + title + "\" publicado com sucesso!"
                        : "Nenhuma alteração no post; repositório enviado ao GitHub.");
            }

            @Override
            public void failed(Exception e) {
                dirty = false;
                reloadPosts(file);
                showError("O arquivo foi salvo localmente, mas a publicação falhou", e);
            }
        });
    }

    private void deletePost() {
        if (current == null) {
            return;
        }
        if (current.getFile() == null) {
            if (JOptionPane.showConfirmDialog(this, "Descartar este post que ainda não foi publicado?",
                    "Descartar", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                deleteLocalImages(pendingImages);
                clearEditor();
            }
            return;
        }
        final File file = current.getFile();
        final String title = current.getTitle().isEmpty() ? file.getName() : current.getTitle();
        final String postPath = repository.relativize(file);
        final String imageFolder = repository.imageFolderFor(current);
        final boolean hasImages = new File(repository.getRoot(), imageFolder).isDirectory();

        String msg = "Excluir o post \"" + title + "\"?\n\nArquivo: " + postPath
                + (hasImages ? "\nImagens: " + imageFolder + "/" : "")
                + "\n\nA exclusão será commitada e enviada ao GitHub.";
        if (JOptionPane.showConfirmDialog(this, msg, "Excluir post", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        final List<String> paths = new ArrayList<String>();
        paths.add(postPath);
        if (hasImages) {
            paths.add(imageFolder);
        }
        runInBackground("Excluindo post...", new Task<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                try {
                    return git.commitAndPush(paths, true, "Remove post: " + title);
                } finally {
                    // Remove também arquivos que nunca foram versionados
                    for (String p : paths) {
                        deleteRecursively(new File(repository.getRoot(), p));
                    }
                }
            }

            @Override
            public void done(Boolean committed) {
                clearEditor();
                reloadPosts(null);
                setStatus("Post \"" + title + "\" excluído.");
            }

            @Override
            public void failed(Exception e) {
                clearEditor();
                reloadPosts(null);
                showError("O post foi removido localmente, mas a publicação da exclusão falhou", e);
            }
        });
    }

    private String importImageIntoRepo(File source) throws IOException {
        if (current == null || repository == null) {
            throw new IOException("Abra ou crie um post antes de inserir imagens.");
        }
        if (current.getFile() == null) {
            String title = titleField.getText().trim();
            String date = dateField.getText().trim();
            if (title.isEmpty() || !date.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                throw new IOException("Preencha o título e a data do post antes de inserir imagens\n"
                        + "(elas são salvas numa pasta com o nome do post).");
            }
            current.setTitle(title);
            current.setDate(date);
        }
        String relative = repository.importImage(current, source);
        pendingImages.add(relative);
        appendLog("Imagem copiada para " + relative);
        return "{{ site.baseurl }}/" + relative;
    }

    private void deleteLocalImages(List<String> images) {
        for (String img : images) {
            try {
                Files.deleteIfExists(new File(repository.getRoot(), img).toPath());
            } catch (IOException ignored) {
                // arquivo em uso; não é crítico
            }
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ------------------------------------------------------------------ estado

    private void markDirty() {
        if (!loading && current != null && !dirty) {
            dirty = true;
            updateActions();
        }
    }

    private boolean confirmDiscard() {
        if (!dirty) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "O post atual tem alterações não publicadas. Deseja descartá-las?",
                "Alterações não publicadas", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            if (current != null && current.getFile() == null && repository != null) {
                deleteLocalImages(pendingImages);
            }
            dirty = false;
            return true;
        }
        return false;
    }

    private void setPostEditingEnabled(boolean enabled) {
        titleField.setEnabled(enabled);
        dateField.setEnabled(enabled);
        layoutField.setEnabled(enabled);
        categoriesField.setEnabled(enabled);
        tagsField.setEnabled(enabled);
        editor.setEditable(enabled);
    }

    private void updateActions() {
        boolean hasRepo = repository != null && !busy;
        openRepoAction.setEnabled(!busy);
        syncAction.setEnabled(hasRepo);
        newAction.setEnabled(hasRepo);
        publishAction.setEnabled(hasRepo && current != null);
        deleteAction.setEnabled(hasRepo && current != null);
        postList.setEnabled(!busy);
        String title = current == null ? "" : (dirty ? " *" : "");
        publishAction.putValue(Action.NAME, "Salvar e publicar" + title);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void appendLog(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                logArea.append(text + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    private void showError(String title, Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JTextArea text = new JTextArea(message, Math.min(15, message.split("\n").length + 1), 60);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), title, JOptionPane.ERROR_MESSAGE);
        setStatus(title + ".");
    }

    // ------------------------------------------------------- tarefas em 2º plano

    private abstract static class Task<T> {
        abstract T call() throws Exception;

        abstract void done(T result);

        void failed(Exception e) {
            throw new UnsupportedOperationException();
        }
    }

    private <T> void runInBackground(String message, final Task<T> task) {
        busy = true;
        updateActions();
        setStatus(message);
        progress.setVisible(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                busy = false;
                progress.setVisible(false);
                setCursor(Cursor.getDefaultCursor());
                updateActions();
                T result;
                try {
                    result = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    Exception cause = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                    try {
                        task.failed(cause);
                    } catch (UnsupportedOperationException notHandled) {
                        showError("Erro", cause);
                    }
                    return;
                }
                task.done(result);
            }
        }.execute();
    }

    // ----------------------------------------------------------------- helpers

    private abstract static class SimpleDocumentListener implements DocumentListener {
        abstract void changed();

        @Override
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            changed();
        }
    }

    private static final class PostCellRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Post post = (Post) value;
            String title = MarkdownRenderer.escapeHtml(post.toString());
            String file = post.getFile() == null ? "" : MarkdownRenderer.escapeHtml(post.getFile().getName());
            String html = "<html><b>" + title + "</b><br><font size=\"-2\" color=\""
                    + (isSelected ? "#dddddd" : "#777777") + "\">" + file + "</font></html>";
            JLabel label = (JLabel) super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return label;
        }
    }
}
