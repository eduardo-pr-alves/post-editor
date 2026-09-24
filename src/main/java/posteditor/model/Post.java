package posteditor.model;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Um post Jekyll: front matter YAML + corpo em Markdown.
 *
 * Os campos conhecidos (title, date, layout, categories, tags) são editáveis;
 * quaisquer outras chaves do front matter são preservadas como texto bruto.
 */
public class Post {

    private static final String[] KNOWN_KEYS = {"layout", "title", "date", "publish_at", "categories", "tags"};

    private File file;
    private boolean draft;
    private String title = "";
    private String date = "";
    /** Data/hora agendada para publicação automática (chave publish_at); vazio se não agendado. */
    private String publishAt = "";
    private String layout = "post";
    private List<String> categories = new ArrayList<String>();
    private List<String> tags = new ArrayList<String>();
    /** Chaves desconhecidas do front matter → linhas brutas (inclui a linha "chave:"). */
    private final Map<String, String> extraFrontMatter = new LinkedHashMap<String, String>();
    private String body = "";

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public boolean isNew() {
        return file == null;
    }

    /** Rascunho: fica em _drafts, só no computador, e não é publicado no site. */
    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date == null ? "" : date;
    }

    public String getPublishAt() {
        return publishAt;
    }

    public void setPublishAt(String publishAt) {
        this.publishAt = publishAt == null ? "" : publishAt.trim();
    }

    public boolean isScheduled() {
        return !publishAt.isEmpty();
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout == null ? "" : layout;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }

    public Map<String, String> getExtraFrontMatter() {
        return extraFrontMatter;
    }

    // ------------------------------------------------------------------ parse

    public static Post parse(String text) {
        Post post = new Post();
        post.layout = "";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("---\n")) {
            post.body = normalized;
            return post;
        }
        int end = normalized.indexOf("\n---", 3);
        while (end >= 0) {
            int after = end + 4;
            if (after >= normalized.length() || normalized.charAt(after) == '\n') {
                break;
            }
            end = normalized.indexOf("\n---", after);
        }
        if (end < 0) {
            post.body = normalized;
            return post;
        }
        String yaml = normalized.substring(4, end + 1);
        int bodyStart = Math.min(normalized.length(), end + 5);
        String body = normalized.substring(bodyStart);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        post.body = body;
        post.parseFrontMatter(yaml);
        return post;
    }

    private void parseFrontMatter(String yaml) {
        String currentKey = null;
        StringBuilder raw = new StringBuilder();
        Map<String, String> entries = new LinkedHashMap<String, String>();
        for (String line : yaml.split("\n", -1)) {
            boolean topLevelKey = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("-") && !line.startsWith("#") && line.indexOf(':') > 0;
            if (topLevelKey) {
                if (currentKey != null) {
                    entries.put(currentKey, trimTrailingNewline(raw.toString()));
                }
                currentKey = line.substring(0, line.indexOf(':')).trim();
                raw.setLength(0);
                raw.append(line).append('\n');
            } else if (currentKey != null) {
                raw.append(line).append('\n');
            }
        }
        if (currentKey != null) {
            entries.put(currentKey, trimTrailingNewline(raw.toString()));
        }

        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String rawEntry = e.getValue();
            if ("title".equals(key)) {
                title = unquote(inlineValue(rawEntry));
            } else if ("publish_at".equals(key)) {
                publishAt = unquote(inlineValue(rawEntry));
            } else if ("date".equals(key)) {
                date = unquote(inlineValue(rawEntry));
            } else if ("layout".equals(key)) {
                layout = unquote(inlineValue(rawEntry));
            } else if ("categories".equals(key) || "category".equals(key)) {
                categories = parseList(rawEntry);
            } else if ("tags".equals(key)) {
                tags = parseList(rawEntry);
            } else {
                extraFrontMatter.put(key, rawEntry);
            }
        }
    }

    private static String trimTrailingNewline(String s) {
        String r = s;
        while (r.endsWith("\n")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String inlineValue(String rawEntry) {
        String first = rawEntry.split("\n", 2)[0];
        return first.substring(first.indexOf(':') + 1).trim();
    }

    /** Aceita "[a, b]", "a b" (formato Jekyll) ou lista YAML em várias linhas. */
    private static List<String> parseList(String rawEntry) {
        List<String> result = new ArrayList<String>();
        String inline = inlineValue(rawEntry);
        if (inline.startsWith("[") && inline.endsWith("]")) {
            for (String item : inline.substring(1, inline.length() - 1).split(",")) {
                addIfNotEmpty(result, unquote(item.trim()));
            }
        } else if (!inline.isEmpty()) {
            if (inline.startsWith("\"") || inline.startsWith("'")) {
                addIfNotEmpty(result, unquote(inline));
            } else {
                for (String item : inline.split("\\s+")) {
                    addIfNotEmpty(result, item);
                }
            }
        } else {
            String[] lines = rawEntry.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.startsWith("-")) {
                    addIfNotEmpty(result, unquote(l.substring(1).trim()));
                }
            }
        }
        return result;
    }

    private static void addIfNotEmpty(List<String> list, String value) {
        if (value != null && !value.isEmpty()) {
            list.add(value);
        }
    }

    static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            String inner = v.substring(1, v.length() - 1);
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char n = inner.charAt(++i);
                    switch (n) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        default:
                            sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    // -------------------------------------------------------------- serialize

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (!layout.trim().isEmpty()) {
            sb.append("layout: ").append(layout.trim()).append('\n');
        }
        sb.append("title: ").append(quote(title)).append('\n');
        if (!date.trim().isEmpty()) {
            sb.append("date: ").append(date.trim()).append('\n');
        }
        if (!publishAt.isEmpty()) {
            sb.append("publish_at: ").append(publishAt).append('\n');
        }
        if (!categories.isEmpty()) {
            sb.append("categories: ").append(formatList(categories)).append('\n');
        }
        if (!tags.isEmpty()) {
            sb.append("tags: ").append(formatList(tags)).append('\n');
        }
        for (Map.Entry<String, String> e : extraFrontMatter.entrySet()) {
            if (!isKnownKey(e.getKey())) {
                sb.append(e.getValue()).append('\n');
            }
        }
        sb.append("---\n\n");
        String b = body;
        sb.append(b);
        if (!b.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean isKnownKey(String key) {
        for (String k : KNOWN_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return "category".equals(key);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String formatList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String item = items.get(i);
            if (item.matches("[\\p{L}\\p{N} _.+#-]+")) {
                sb.append(item);
            } else {
                sb.append(quote(item));
            }
        }
        return sb.append(']').toString();
    }

    /** Converte "a, b, c" em lista. */
    public static List<String> splitCommaList(String text) {
        List<String> result = new ArrayList<String>();
        for (String part : text.split(",")) {
            addIfNotEmpty(result, part.trim());
        }
        return result;
    }

    public static String joinCommaList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return title.isEmpty() ? (file == null ? "(novo post)" : file.getName()) : title;
    }
}
