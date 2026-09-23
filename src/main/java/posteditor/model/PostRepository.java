package posteditor.model;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Acesso aos arquivos de posts e imagens dentro do repositório do blog.
 */
public class PostRepository {

    private final File root;
    private final String postsDir;
    private final String draftsDir;
    private final String imagesDir;

    public PostRepository(File root, String postsDir, String draftsDir, String imagesDir) {
        this.root = root;
        this.postsDir = postsDir;
        this.draftsDir = draftsDir;
        this.imagesDir = imagesDir;
    }

    public File getRoot() {
        return root;
    }

    public File getPostsFolder() {
        return new File(root, postsDir);
    }

    public File getDraftsFolder() {
        return new File(root, draftsDir);
    }

    /** Rascunhos (ordenados por nome) seguidos dos posts publicados (mais novos primeiro). */
    public List<Post> listPosts() throws IOException {
        List<Post> posts = new ArrayList<Post>();
        for (File f : listMarkdown(getDraftsFolder(), false)) {
            posts.add(load(f));
        }
        for (File f : listMarkdown(getPostsFolder(), true)) {
            posts.add(load(f));
        }
        return posts;
    }

    private static List<File> listMarkdown(File folder, final boolean newestFirst) {
        File[] files = folder.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && (name.endsWith(".md") || name.endsWith(".markdown"));
            }
        });
        if (files == null) {
            return Collections.emptyList();
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return newestFirst ? b.getName().compareTo(a.getName()) : a.getName().compareTo(b.getName());
            }
        });
        return Arrays.asList(files);
    }

    public Post load(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Post post = Post.parse(text);
        post.setFile(file);
        post.setDraft(sameFile(file.getParentFile(), getDraftsFolder()));
        return post;
    }

    /**
     * Grava o post no disco, na pasta correspondente ao seu estado:
     * rascunhos em _drafts/slug.md e posts em _posts/AAAA-MM-DD-slug.md.
     *
     * Posts que já estão na pasta certa mantêm o nome do arquivo (para não
     * quebrar links). Quando o post muda de pasta (ex.: rascunho publicado),
     * o arquivo antigo é apagado e as imagens são movidas para a pasta de
     * imagens do novo nome, com os links do texto atualizados.
     */
    public File save(Post post) throws IOException {
        File oldFile = post.getFile();
        File folder = post.isDraft() ? getDraftsFolder() : getPostsFolder();
        if (oldFile != null && sameFile(oldFile.getParentFile(), folder)) {
            write(oldFile, post);
            return oldFile;
        }
        String oldImages = imageFolderFor(post);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta " + folder);
        }
        String base = post.isDraft() ? slugify(post.getTitle()) : datedSlug(post);
        File file = uniqueFile(folder, base, ".md");
        post.setFile(file);
        moveImages(post, oldImages, imageFolderFor(post));
        write(file, post);
        if (oldFile != null) {
            Files.deleteIfExists(oldFile.toPath());
        }
        return file;
    }

    private static void write(File file, Post post) throws IOException {
        Files.write(file.toPath(), post.toMarkdown().getBytes(StandardCharsets.UTF_8));
    }

    /** Move as imagens de uma pasta para outra e atualiza os links no corpo do post. */
    private void moveImages(Post post, String from, String to) throws IOException {
        File source = new File(root, from);
        if (from.equals(to) || !source.isDirectory()) {
            return;
        }
        File target = new File(root, to);
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta " + target);
        }
        File[] images = source.listFiles();
        if (images != null) {
            for (File img : images) {
                if (img.isFile()) {
                    Files.move(img.toPath(), new File(target, img.getName()).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        source.delete();
        post.setBody(post.getBody().replace(from + "/", to + "/"));
    }

    private static boolean sameFile(File a, File b) {
        return a != null && b != null
                && a.toPath().toAbsolutePath().normalize().equals(b.toPath().toAbsolutePath().normalize());
    }

    /** Nome base do arquivo (sem extensão); para posts ainda não salvos, data + slug do título. */
    public static String baseName(Post post) {
        if (post.getFile() != null) {
            String name = post.getFile().getName();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return datedSlug(post);
    }

    private static String datedSlug(Post post) {
        String date = post.getDate().trim();
        String day = date.length() >= 10 ? date.substring(0, 10) : date;
        return day + "-" + slugify(post.getTitle());
    }

    /** Pasta de imagens do post (relativa à raiz do repositório, com "/"). */
    public String imageFolderFor(Post post) {
        return trimSlashes(imagesDir) + "/" + baseName(post);
    }

    /**
     * Copia uma imagem para a pasta de imagens do post e devolve o caminho
     * relativo à raiz do repositório (ex.: assets/images/2024-01-01-meu-post/foto.png).
     */
    public String importImage(Post post, File source) throws IOException {
        String relFolder = imageFolderFor(post);
        File folder = new File(root, relFolder);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta " + folder);
        }
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = slugify(dot > 0 ? name.substring(0, dot) : name);
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        File target = uniqueFile(folder, base, ext);
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return relFolder + "/" + target.getName();
    }

    /** Caminho relativo à raiz do repositório, com separador "/". */
    public String relativize(File file) {
        return root.toPath().toAbsolutePath().normalize()
                .relativize(file.toPath().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static File uniqueFile(File folder, String base, String ext) {
        File candidate = new File(folder, base + ext);
        int n = 2;
        while (candidate.exists()) {
            candidate = new File(folder, base + "-" + n + ext);
            n++;
        }
        return candidate;
    }

    private static String trimSlashes(String s) {
        String r = s.replace('\\', '/');
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    public static String slugify(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 80) {
            s = s.substring(0, 80).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "post" : s;
    }
}
