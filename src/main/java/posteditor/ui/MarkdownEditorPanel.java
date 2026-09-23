package posteditor.ui;

import posteditor.markdown.MarkdownRenderer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.InputEvent;
import java.io.File;
import java.io.IOException;

/**
 * Editor de Markdown com barra de formatação e pré-visualização ao vivo.
 */
public class MarkdownEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Copia uma imagem local para o repositório e devolve a URL a usar no Markdown. */
    public interface ImageImporter {
        String importImage(File file) throws IOException;
    }

    private static final String[] LANGUAGES = {
        "", "java", "javascript", "typescript", "python", "c", "cpp", "csharp", "go", "rust", "kotlin",
        "php", "ruby", "swift", "sql", "bash", "powershell", "html", "css", "scss", "json", "yaml", "xml",
        "markdown", "dockerfile", "plaintext"
    };

    private static final String[] PREVIEW_CSS = {
        "body { font-family: SansSerif; font-size: 12pt; color: #24292f; margin: 12px; }",
        "h1 { font-size: 22pt; margin-top: 12px; margin-bottom: 6px; }",
        "h2 { font-size: 18pt; margin-top: 12px; margin-bottom: 6px; }",
        "h3 { font-size: 15pt; margin-top: 10px; margin-bottom: 4px; }",
        "h4, h5, h6 { font-size: 13pt; margin-top: 8px; margin-bottom: 4px; }",
        "p { margin-top: 4px; margin-bottom: 8px; }",
        "pre { background-color: #f6f8fa; padding: 8px; margin-top: 0px; margin-bottom: 10px; }",
        "code { font-family: Monospaced; font-size: 11pt; background-color: #eff1f3; }",
        "pre code { background-color: #f6f8fa; }",
        ".lang { font-family: Monospaced; font-size: 9pt; color: #57606a; background-color: #eaeef2;"
            + " padding: 2px 8px; margin-top: 6px; }",
        "blockquote { color: #57606a; margin-left: 12px; padding-left: 10px; border-left: 4px solid #d0d7de; }",
        "th { background-color: #f6f8fa; font-weight: bold; }",
        "a { color: #0969da; }",
        "ul, ol { margin-left: 24px; }",
        ".footnotes { font-size: 10pt; color: #57606a; }"
    };

    /** Kit HTML com folha de estilo própria (sem alterar o estilo global do Swing). */
    private static final class PreviewEditorKit extends HTMLEditorKit {
        private static final long serialVersionUID = 1L;

        @Override
        public Document createDefaultDocument() {
            StyleSheet styles = new StyleSheet();
            styles.addStyleSheet(getStyleSheet());
            for (String rule : PREVIEW_CSS) {
                styles.addRule(rule);
            }
            HTMLDocument doc = new HTMLDocument(styles);
            doc.setParser(getParser());
            doc.setAsynchronousLoadPriority(4);
            doc.setTokenThreshold(100);
            return doc;
        }
    }

    private final JTextArea editor = new JTextArea();
    private final JEditorPane preview = new JEditorPane();
    private final JScrollPane editorScroll;
    private final JScrollPane previewScroll;
    private final UndoManager undo = new UndoManager();
    private final Timer renderTimer;
    private MarkdownRenderer renderer = new MarkdownRenderer(null);
    private ImageImporter imageImporter;
    private File lastImageDir;

    public MarkdownEditorPanel() {
        super(new BorderLayout());

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setTabSize(4);
        editor.setMargin(new Insets(8, 8, 8, 8));
        editor.getDocument().addUndoableEditListener(new UndoableEditListener() {
            @Override
            public void undoableEditHappened(UndoableEditEvent e) {
                undo.addEdit(e.getEdit());
            }
        });

        preview.setEditorKit(new PreviewEditorKit());
        preview.setEditable(false);
        preview.addHyperlinkListener(new HyperlinkListener() {
            @Override
            public void hyperlinkUpdate(HyperlinkEvent e) {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null
                        && Desktop.isDesktopSupported()) {
                    try {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    } catch (Exception ignored) {
                        // link inválido ou sem navegador
                    }
                }
            }
        });

        editorScroll = new JScrollPane(editor);
        previewScroll = new JScrollPane(preview);
        // Tamanhos preferidos iguais para o divisor começar no meio
        editorScroll.setPreferredSize(new Dimension(300, 300));
        previewScroll.setPreferredSize(new Dimension(300, 300));
        editorScroll.setBorder(BorderFactory.createTitledBorder("Markdown"));
        previewScroll.setBorder(BorderFactory.createTitledBorder("Pré-visualização"));
        editorScroll.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                syncPreviewScroll();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScroll, previewScroll);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);

        add(createToolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        renderTimer = new Timer(350, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                renderPreview();
            }
        });
        renderTimer.setRepeats(false);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                renderTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                renderTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                renderTimer.restart();
            }
        });

        installShortcuts();
    }

    // ------------------------------------------------------------ API pública

    public void setRenderer(MarkdownRenderer renderer) {
        this.renderer = renderer;
        renderPreview();
    }

    public void setImageImporter(ImageImporter importer) {
        this.imageImporter = importer;
    }

    public String getText() {
        return editor.getText();
    }

    /** Substitui o conteúdo e limpa o histórico de desfazer. */
    public void setText(String text) {
        editor.setText(text);
        editor.setCaretPosition(0);
        undo.discardAllEdits();
        renderPreview();
    }

    public void addDocumentListener(DocumentListener listener) {
        editor.getDocument().addDocumentListener(listener);
    }

    public void setEditable(boolean editable) {
        editor.setEditable(editable);
    }

    public void focusEditor() {
        editor.requestFocusInWindow();
    }

    // ---------------------------------------------------------------- toolbar

    private JToolBar createToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        addButton(bar, "H1", "Título 1", new Runnable() {
            public void run() {
                heading(1);
            }
        });
        addButton(bar, "H2", "Título 2", new Runnable() {
            public void run() {
                heading(2);
            }
        });
        addButton(bar, "H3", "Título 3", new Runnable() {
            public void run() {
                heading(3);
            }
        });
        bar.addSeparator();
        JButton bold = addButton(bar, "B", "Negrito (Ctrl+B)", new Runnable() {
            public void run() {
                wrap("**", "**", "texto em negrito");
            }
        });
        bold.setFont(bold.getFont().deriveFont(Font.BOLD));
        JButton italic = addButton(bar, "I", "Itálico (Ctrl+I)", new Runnable() {
            public void run() {
                wrap("*", "*", "texto em itálico");
            }
        });
        italic.setFont(italic.getFont().deriveFont(Font.ITALIC));
        addButton(bar, "S", "Tachado", new Runnable() {
            public void run() {
                wrap("~~", "~~", "texto tachado");
            }
        });
        addButton(bar, "`c`", "Código inline", new Runnable() {
            public void run() {
                wrap("`", "`", "codigo");
            }
        });
        bar.addSeparator();
        addButton(bar, "Link", "Inserir link (Ctrl+K)", new Runnable() {
            public void run() {
                insertLink();
            }
        });
        addButton(bar, "Imagem", "Inserir imagem do computador ou da internet", new Runnable() {
            public void run() {
                insertImage();
            }
        });
        addButton(bar, "Tabela", "Inserir tabela (ou converter seleção CSV/planilha em tabela)", new Runnable() {
            public void run() {
                insertTable();
            }
        });
        addButton(bar, "{ } Código", "Bloco de código com destaque de linguagem", new Runnable() {
            public void run() {
                insertCodeBlock();
            }
        });
        bar.addSeparator();
        addButton(bar, "“ Citação", "Citação", new Runnable() {
            public void run() {
                prefixLines("> ", false);
            }
        });
        addButton(bar, "• Lista", "Lista com marcadores", new Runnable() {
            public void run() {
                prefixLines("- ", false);
            }
        });
        addButton(bar, "1. Lista", "Lista numerada", new Runnable() {
            public void run() {
                prefixLines(null, true);
            }
        });
        addButton(bar, "[ ] Tarefa", "Lista de tarefas", new Runnable() {
            public void run() {
                prefixLines("- [ ] ", false);
            }
        });
        bar.addSeparator();
        addButton(bar, "Linha", "Linha horizontal", new Runnable() {
            public void run() {
                insertBlock("---");
            }
        });
        addButton(bar, "Nota", "Nota de rodapé", new Runnable() {
            public void run() {
                insertFootnote();
            }
        });
        addButton(bar, "Recolhível", "Seção recolhível (<details>)", new Runnable() {
            public void run() {
                String sel = editor.getSelectedText();
                insertBlock("<details markdown=\"1\">\n<summary>Clique para expandir</summary>\n\n"
                        + (sel == null ? "Conteúdo escondido." : sel) + "\n\n</details>");
            }
        });
        return bar;
    }

    private JButton addButton(JToolBar bar, String label, String tooltip, final Runnable action) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (editor.isEditable()) {
                    action.run();
                    editor.requestFocusInWindow();
                }
            }
        });
        bar.add(b);
        return b;
    }

    private void installShortcuts() {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        bind(KeyStroke.getKeyStroke('Z', menu), "undo", new Runnable() {
            public void run() {
                try {
                    if (undo.canUndo()) {
                        undo.undo();
                    }
                } catch (CannotUndoException ignored) {
                    // nada a desfazer
                }
            }
        });
        Runnable redo = new Runnable() {
            public void run() {
                try {
                    if (undo.canRedo()) {
                        undo.redo();
                    }
                } catch (CannotRedoException ignored) {
                    // nada a refazer
                }
            }
        };
        bind(KeyStroke.getKeyStroke('Y', menu), "redo", redo);
        bind(KeyStroke.getKeyStroke('Z', menu | InputEvent.SHIFT_MASK), "redo2", redo);
        bind(KeyStroke.getKeyStroke('B', menu), "bold", new Runnable() {
            public void run() {
                wrap("**", "**", "texto em negrito");
            }
        });
        bind(KeyStroke.getKeyStroke('I', menu), "italic", new Runnable() {
            public void run() {
                wrap("*", "*", "texto em itálico");
            }
        });
        bind(KeyStroke.getKeyStroke('K', menu), "link", new Runnable() {
            public void run() {
                insertLink();
            }
        });
    }

    private void bind(KeyStroke key, String name, final Runnable action) {
        editor.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        editor.getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (editor.isEditable()) {
                    action.run();
                }
            }
        });
    }

    // ------------------------------------------------------------- formatação

    /** Envolve a seleção com prefixo/sufixo; se já estiver envolvida, remove. */
    private void wrap(String prefix, String suffix, String placeholder) {
        int start = editor.getSelectionStart();
        int end = editor.getSelectionEnd();
        String text = editor.getText();
        if (start >= prefix.length() && end + suffix.length() <= text.length()
                && text.startsWith(prefix, start - prefix.length()) && text.startsWith(suffix, end)
                && start != end) {
            editor.replaceRange("", end, end + suffix.length());
            editor.replaceRange("", start - prefix.length(), start);
            editor.select(start - prefix.length(), end - prefix.length());
            return;
        }
        String selected = start == end ? placeholder : text.substring(start, end);
        editor.replaceRange(prefix + selected + suffix, start, end);
        editor.select(start + prefix.length(), start + prefix.length() + selected.length());
    }

    private void heading(int level) {
        StringBuilder hashes = new StringBuilder();
        for (int i = 0; i < level; i++) {
            hashes.append('#');
        }
        try {
            int line = editor.getLineOfOffset(editor.getSelectionStart());
            int ls = editor.getLineStartOffset(line);
            int le = editor.getLineEndOffset(line);
            String content = editor.getText(ls, le - ls);
            boolean newline = content.endsWith("\n");
            if (newline) {
                content = content.substring(0, content.length() - 1);
            }
            String stripped = content.replaceFirst("^\\s*#{1,6}\\s*", "");
            String prefix = hashes + " ";
            String replacement = content.startsWith(prefix) && !content.startsWith(prefix + "#")
                    ? stripped : prefix + (stripped.isEmpty() ? "Título" : stripped);
            editor.replaceRange(replacement, ls, ls + content.length());
            editor.select(ls + replacement.length(), ls + replacement.length());
        } catch (BadLocationException ignored) {
            // posição inválida
        }
    }

    /** Aplica (ou remove) um prefixo a todas as linhas selecionadas. */
    private void prefixLines(String prefix, boolean numbered) {
        try {
            int firstLine = editor.getLineOfOffset(editor.getSelectionStart());
            int endOffset = editor.getSelectionEnd();
            if (endOffset > editor.getSelectionStart() && editor.getText(endOffset - 1, 1).equals("\n")) {
                endOffset--;
            }
            int lastLine = editor.getLineOfOffset(endOffset);
            int ls = editor.getLineStartOffset(firstLine);
            int le = editor.getLineEndOffset(lastLine);
            String block = editor.getText(ls, le - ls);
            boolean trailingNewline = block.endsWith("\n");
            if (trailingNewline) {
                block = block.substring(0, block.length() - 1);
            }
            String[] lines = block.split("\n", -1);

            boolean allPrefixed = true;
            for (int i = 0; i < lines.length; i++) {
                boolean has = numbered ? lines[i].matches("^\\d+\\.\\s.*") : lines[i].startsWith(prefix);
                if (!has) {
                    allPrefixed = false;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (allPrefixed) {
                    l = numbered ? l.replaceFirst("^\\d+\\.\\s", "") : l.substring(prefix.length());
                } else {
                    l = (numbered ? (i + 1) + ". " : prefix) + l;
                }
                sb.append(l);
                if (i < lines.length - 1) {
                    sb.append('\n');
                }
            }
            editor.replaceRange(sb.toString(), ls, ls + block.length());
            editor.select(ls + sb.length(), ls + sb.length());
        } catch (BadLocationException ignored) {
            // posição inválida
        }
    }

    /** Insere um bloco garantindo linhas em branco ao redor. */
    private void insertBlock(String block) {
        int start = editor.getSelectionStart();
        int end = editor.getSelectionEnd();
        String text = editor.getText();
        String before = text.substring(0, start);
        String after = text.substring(end);
        StringBuilder sb = new StringBuilder();
        if (!before.isEmpty() && !before.endsWith("\n\n")) {
            sb.append(before.endsWith("\n") ? "\n" : "\n\n");
        }
        sb.append(block);
        int caret = start + sb.length();
        if (!after.startsWith("\n\n")) {
            sb.append(after.startsWith("\n") ? "\n" : "\n\n");
        }
        editor.replaceRange(sb.toString(), start, end);
        editor.setCaretPosition(Math.min(caret, editor.getDocument().getLength()));
    }

    private void insertLink() {
        String selected = editor.getSelectedText();
        JTextField textField = new JTextField(selected == null ? "" : selected, 30);
        JTextField urlField = new JTextField("https://", 30);
        JPanel panel = form(new String[]{"Texto:", "URL:"}, new JComponent[]{textField, urlField});
        if (JOptionPane.showConfirmDialog(this, panel, "Inserir link", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String text = textField.getText().trim();
        String url = urlField.getText().trim();
        if (text.isEmpty()) {
            text = url;
        }
        editor.replaceSelection("[" + text + "](" + url.replace(" ", "%20") + ")");
    }

    private void insertImage() {
        final JRadioButton local = new JRadioButton("Arquivo do computador (será enviado ao repositório)", true);
        final JRadioButton web = new JRadioButton("Endereço na internet (URL)");
        ButtonGroup group = new ButtonGroup();
        group.add(local);
        group.add(web);
        final JTextField pathField = new JTextField(30);
        final JButton browse = new JButton("Procurar...");
        final JTextField urlField = new JTextField("https://", 30);
        String selected = editor.getSelectedText();
        JTextField altField = new JTextField(selected == null ? "" : selected, 30);
        JTextField titleField = new JTextField(30);

        browse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser(lastImageDir);
                chooser.setFileFilter(new FileNameExtensionFilter("Imagens", "png", "jpg", "jpeg", "gif",
                        "webp", "svg", "bmp", "ico"));
                if (chooser.showOpenDialog(MarkdownEditorPanel.this) == JFileChooser.APPROVE_OPTION) {
                    pathField.setText(chooser.getSelectedFile().getAbsolutePath());
                    lastImageDir = chooser.getSelectedFile().getParentFile();
                    local.setSelected(true);
                }
            }
        });

        JPanel filePanel = new JPanel(new BorderLayout(4, 0));
        filePanel.add(pathField, BorderLayout.CENTER);
        filePanel.add(browse, BorderLayout.EAST);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);
        c.weightx = 1;
        panel.add(local, c);
        panel.add(filePanel, c);
        panel.add(web, c);
        panel.add(urlField, c);
        panel.add(new JLabel("Texto alternativo (descrição da imagem):"), c);
        panel.add(altField, c);
        panel.add(new JLabel("Legenda ao passar o mouse (opcional):"), c);
        panel.add(titleField, c);

        if (JOptionPane.showConfirmDialog(this, panel, "Inserir imagem", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String url;
        if (local.isSelected()) {
            File file = new File(pathField.getText().trim());
            if (!file.isFile()) {
                JOptionPane.showMessageDialog(this, "Selecione um arquivo de imagem válido.", "Imagem",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (imageImporter == null) {
                JOptionPane.showMessageDialog(this, "Abra o repositório do blog antes de inserir imagens.",
                        "Imagem", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                url = imageImporter.importImage(file);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Não foi possível inserir a imagem",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (url == null) {
                return;
            }
        } else {
            url = urlField.getText().trim().replace(" ", "%20");
        }
        String alt = altField.getText().trim().replace("]", "\\]");
        String title = titleField.getText().trim().replace("\"", "'");
        editor.replaceSelection("![" + alt + "](" + url + (title.isEmpty() ? "" : " \"" + title + "\"") + ")");
    }

    private void insertTable() {
        String selected = editor.getSelectedText();
        if (selected != null && selected.contains("\n")) {
            String table = TableDialog.fromDelimited(selected);
            if (table != null) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "Converter o texto selecionado (CSV/planilha) em tabela?", "Tabela",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                    return;
                }
                if (answer == JOptionPane.YES_OPTION) {
                    insertBlock(table.trim());
                    return;
                }
            }
        }
        String table = TableDialog.show(this);
        if (table != null) {
            insertBlock(table.trim());
        }
    }

    private void insertCodeBlock() {
        JComboBox<String> lang = new JComboBox<String>(LANGUAGES);
        lang.setEditable(true);
        JPanel panel = form(new String[]{"Linguagem:"}, new JComponent[]{lang});
        if (JOptionPane.showConfirmDialog(this, panel, "Bloco de código", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Object value = lang.getEditor().getItem();
        String language = value == null ? "" : value.toString().trim();
        String selected = editor.getSelectedText();
        String code = selected == null ? "// seu código aqui" : selected.replaceAll("\n+$", "");
        int start = editor.getSelectionStart();
        insertBlock("```" + language + "\n" + code + "\n```");
        if (selected == null) {
            int idx = editor.getText().indexOf(code, start);
            if (idx >= 0) {
                editor.select(idx, idx + code.length());
            }
        }
    }

    private void insertFootnote() {
        String text = editor.getText();
        int n = 1;
        while (text.contains("[^" + n + "]")) {
            n++;
        }
        String note = JOptionPane.showInputDialog(this, "Texto da nota de rodapé:", "Nota de rodapé",
                JOptionPane.PLAIN_MESSAGE);
        if (note == null) {
            return;
        }
        int caret = editor.getSelectionEnd();
        editor.insert("[^" + n + "]", caret);
        String current = editor.getText();
        String suffix = (current.endsWith("\n\n") ? "" : current.endsWith("\n") ? "\n" : "\n\n")
                + "[^" + n + "]: " + note + "\n";
        editor.append(suffix);
        editor.setCaretPosition(caret + ("[^" + n + "]").length());
    }

    private static JPanel form(String[] labels, JComponent[] fields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            panel.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            c.weightx = 1;
            panel.add(fields[i], c);
        }
        return panel;
    }

    // --------------------------------------------------------- pré-visualização

    private void renderPreview() {
        renderTimer.stop();
        String html;
        try {
            int width = previewScroll.getViewport().getExtentSize().width;
            if (width > 0) {
                renderer.setMaxImageWidth(width - 40);
            }
            html = renderer.render(editor.getText());
        } catch (RuntimeException e) {
            html = "<html><body><p><b>Erro na pré-visualização:</b> "
                    + MarkdownRenderer.escapeHtml(String.valueOf(e)) + "</p></body></html>";
        }
        try {
            preview.setText(html);
        } catch (RuntimeException e) {
            preview.setText("<html><body><p>Não foi possível exibir a pré-visualização.</p></body></html>");
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                syncPreviewScroll();
            }
        });
    }

    /** Mantém a pré-visualização na mesma posição relativa do editor. */
    private void syncPreviewScroll() {
        JScrollBar source = editorScroll.getVerticalScrollBar();
        JScrollBar target = previewScroll.getVerticalScrollBar();
        int sourceRange = source.getMaximum() - source.getVisibleAmount() - source.getMinimum();
        int targetRange = target.getMaximum() - target.getVisibleAmount() - target.getMinimum();
        if (sourceRange <= 0 || targetRange <= 0) {
            return;
        }
        double ratio = (source.getValue() - source.getMinimum()) / (double) sourceRange;
        target.setValue(target.getMinimum() + (int) Math.round(ratio * targetRange));
    }
}
