package posteditor.git;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executa comandos do git instalado na máquina. As credenciais usadas no push
 * são as já configuradas no git (SSH ou Git Credential Manager).
 */
public class GitService {

    /** Recebe cada comando executado e sua saída, para exibir no log. */
    public interface Listener {
        void onOutput(String text);
    }

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final File repo;
    private final Listener listener;

    public GitService(File repo, Listener listener) {
        this.repo = repo;
        this.listener = listener;
    }

    public Result run(String... args) throws IOException {
        return run(Arrays.asList(args));
    }

    public Result run(List<String> args) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(args);
        log("$ " + join(command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repo);
        pb.redirectErrorStream(true);
        // Evita que o git fique travado esperando senha no terminal (não há terminal).
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Não foi possível executar o git. Verifique se ele está instalado e no PATH.", e);
        }
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Comando git interrompido", e);
        }
        if (!output.trim().isEmpty()) {
            log(output.trim());
        }
        return new Result(code, output);
    }

    private Result runOrFail(String... args) throws IOException {
        Result r = run(args);
        if (!r.ok()) {
            throw new GitException("Falha em 'git " + join(Arrays.asList(args)) + "':\n" + r.output.trim());
        }
        return r;
    }

    public boolean isRepository() {
        try {
            Result r = run("rev-parse", "--is-inside-work-tree");
            return r.ok() && r.output.trim().equals("true");
        } catch (IOException e) {
            return false;
        }
    }

    public String currentBranch() throws IOException {
        return runOrFail("rev-parse", "--abbrev-ref", "HEAD").output.trim();
    }

    public String remoteName() throws IOException {
        Result r = run("remote");
        if (!r.ok()) {
            return null;
        }
        String[] remotes = r.output.trim().split("\\s+");
        for (String remote : remotes) {
            if ("origin".equals(remote)) {
                return remote;
            }
        }
        return remotes.length > 0 && !remotes[0].isEmpty() ? remotes[0] : null;
    }

    /** Indica se o caminho (arquivo ou pasta) tem arquivos versionados no índice. */
    public boolean isTracked(String path) throws IOException {
        Result r = run("ls-files", "--", path);
        return r.ok() && !r.output.trim().isEmpty();
    }

    /** Atualiza o repositório local com o remoto (se houver remoto configurado). */
    public void pull() throws IOException {
        String remote = remoteName();
        if (remote == null) {
            log("Nenhum remoto configurado; pull ignorado.");
            return;
        }
        runOrFail("pull", "--rebase", "--autostash", remote, currentBranch());
    }

    /**
     * Adiciona os caminhos, faz commit apenas deles e envia para o remoto.
     *
     * @param paths   caminhos relativos à raiz do repositório
     * @param removed se true, os caminhos são removidos do repositório (git rm)
     * @return false se não havia nada para commitar
     */
    public boolean commitAndPush(List<String> requestedPaths, boolean removed, String message) throws IOException {
        List<String> paths = new ArrayList<String>();
        for (String p : requestedPaths) {
            // Só é possível remover/commitar pelo caminho o que o git já conhece.
            if (!removed || isTracked(p)) {
                paths.add(p);
            }
        }
        if (paths.isEmpty()) {
            log("Nenhum arquivo versionado a remover.");
            return false;
        }
        List<String> args = new ArrayList<String>();
        if (removed) {
            args.addAll(Arrays.asList("rm", "-r", "-f", "-q", "--ignore-unmatch", "--"));
        } else {
            args.addAll(Arrays.asList("add", "--"));
        }
        args.addAll(paths);
        Result stage = run(args);
        if (!stage.ok()) {
            throw new GitException("Falha ao preparar os arquivos:\n" + stage.output.trim());
        }

        List<String> diff = new ArrayList<String>(Arrays.asList("diff", "--cached", "--quiet", "--"));
        diff.addAll(paths);
        boolean committed = false;
        if (!run(diff).ok()) {
            List<String> commit = new ArrayList<String>(Arrays.asList("commit", "-m", message, "--"));
            commit.addAll(paths);
            Result c = run(commit);
            if (!c.ok()) {
                throw new GitException("Falha no commit:\n" + c.output.trim()
                        + "\n\nDica: configure seu nome/e-mail com 'git config --global user.name' e 'user.email'.");
            }
            committed = true;
        } else {
            log("Nenhuma alteração para commitar.");
        }
        push();
        return committed;
    }

    public void push() throws IOException {
        String remote = remoteName();
        if (remote == null) {
            throw new GitException("O repositório não tem remoto configurado (ex.: origin). O commit foi feito apenas localmente.");
        }
        String branch = currentBranch();
        Result r = run("push", "-u", remote, branch);
        if (r.ok()) {
            return;
        }
        String out = r.output.toLowerCase();
        if (out.contains("rejected") || out.contains("fetch first") || out.contains("non-fast-forward")) {
            log("Remoto tem alterações novas; sincronizando e tentando novamente...");
            runOrFail("pull", "--rebase", "--autostash", remote, branch);
            runOrFail("push", "-u", remote, branch);
            return;
        }
        throw new GitException("Falha no push:\n" + r.output.trim());
    }

    private void log(String text) {
        if (listener != null) {
            listener.onOutput(text);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.contains(" ") ? "\"" + p + "\"" : p);
        }
        return sb.toString();
    }

    public static class GitException extends IOException {
        private static final long serialVersionUID = 1L;

        public GitException(String message) {
            super(message);
        }
    }
}
