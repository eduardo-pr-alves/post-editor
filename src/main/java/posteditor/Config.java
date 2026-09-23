package posteditor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Configurações persistidas em ~/.post-editor.properties.
 */
public final class Config {

    private static final File FILE = new File(System.getProperty("user.home"), ".post-editor.properties");

    private static final String REPO_PATH = "repo.path";
    private static final String POSTS_DIR = "posts.dir";
    private static final String DRAFTS_DIR = "drafts.dir";
    private static final String IMAGES_DIR = "images.dir";
    private static final String DEFAULT_LAYOUT = "default.layout";

    private final Properties props = new Properties();

    private Config() {
    }

    public static Config load() {
        Config config = new Config();
        if (FILE.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(FILE);
                config.props.load(in);
            } catch (IOException ignored) {
                // Usa valores padrão
            } finally {
                closeQuietly(in);
            }
        }
        return config;
    }

    public void save() {
        OutputStream out = null;
        try {
            out = new FileOutputStream(FILE);
            props.store(out, "Post Editor");
        } catch (IOException ignored) {
            // Falha ao salvar configurações não é crítica
        } finally {
            closeQuietly(out);
        }
    }

    public File getRepoPath() {
        String path = props.getProperty(REPO_PATH);
        return path == null ? null : new File(path);
    }

    public void setRepoPath(File repo) {
        props.setProperty(REPO_PATH, repo.getAbsolutePath());
    }

    /** Pasta dos posts, relativa à raiz do repositório (padrão Jekyll: _posts). */
    public String getPostsDir() {
        return props.getProperty(POSTS_DIR, "_posts");
    }

    /** Pasta dos rascunhos, relativa à raiz do repositório (padrão Jekyll: _drafts). */
    public String getDraftsDir() {
        return props.getProperty(DRAFTS_DIR, "_drafts");
    }

    /** Pasta onde as imagens inseridas são copiadas, relativa à raiz do repositório. */
    public String getImagesDir() {
        return props.getProperty(IMAGES_DIR, "assets/images");
    }

    public String getDefaultLayout() {
        return props.getProperty(DEFAULT_LAYOUT, "post");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nada a fazer
            }
        }
    }
}
