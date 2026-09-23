package posteditor.markdown;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversor Markdown → HTML usado na pré-visualização do editor.
 *
 * Suporta a sintaxe comum do GitHub/kramdown: títulos, ênfases, tachado,
 * links (inline e por referência), imagens, código inline e em bloco,
 * citações, listas (aninhadas, ordenadas e de tarefas), tabelas, linhas
 * horizontais, notas de rodapé e HTML embutido.
 */
public class MarkdownRenderer {

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})(?:\\s+(.*?))?(?:\\s+#+)?\\s*$");
    private static final Pattern HR = Pattern.compile("^\\s{0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])(?:\\s+(.*))?$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern HTML_BLOCK = Pattern.compile(
            "^\\s{0,3}<(?:/?(?i:address|article|aside|audio|blockquote|center|details|dialog|div|dl|figure|figcaption"
            + "|footer|form|h[1-6]|header|hr|iframe|main|nav|ol|p|pre|script|section|style|summary|table|tbody|td"
            + "|tfoot|th|thead|tr|ul|video)(?:\\s|/?>|$)|!--).*$");
    private static final Pattern LINK_DEF = Pattern.compile("^\\s{0,3}\\[([^\\]^][^\\]]*)\\]:\\s*<?(\\S+?)>?(?:\\s+[\"'(](.*)[\"')])?\\s*$");
    private static final Pattern FOOTNOTE_DEF = Pattern.compile("^\\s{0,3}\\[\\^([^\\]]+)\\]:\\s*(.*)$");
    private static final Pattern SETEXT_H1 = Pattern.compile("^\\s{0,3}=+\\s*$");
    private static final Pattern SETEXT_H2 = Pattern.compile("^\\s{0,3}-+\\s*$");
    private static final Pattern KRAMDOWN_ATTR = Pattern.compile("^\\s*\\{:.*\\}\\s*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\u0001(\\d+)\u0002");
    private static final Pattern BARE_URL = Pattern.compile("(?<![\"'=\\w/])(https?://[^\\s<\u0001]+[^\\s<.,;:!?)\\]'\"\u0001])");
    private static final Pattern INLINE_HTML = Pattern.compile("^</?[a-zA-Z][a-zA-Z0-9-]*(?:\\s+[^<>]*)?/?>");
    private static final Pattern AUTOLINK = Pattern.compile("^<((?:https?|ftp|mailto):[^\\s<>]+)>");
    private static final Pattern LIQUID_TAG = Pattern.compile("\\{\\{(.*?)\\}\\}");
    private static final Pattern LIQUID_BASEURL = Pattern.compile("\\{\\{\\s*site\\.baseurl\\s*\\}\\}");
    private static final Pattern LIQUID_URL_FILTER = Pattern.compile(
            "\\{\\{\\s*[\"']([^\"']*)[\"']\\s*\\|\\s*(?:relative_url|absolute_url)\\s*\\}\\}");

    private final File repoRoot;
    private int maxImageWidth = 560;
    private final Map<String, int[]> imageSizeCache = new HashMap<String, int[]>();

    private Map<String, String[]> linkDefs;
    private Map<String, String> footnoteDefs;
    private List<String> footnoteOrder;
    private List<String> slots;

    /**
     * @param repoRoot raiz do repositório, usada para resolver imagens locais
     *                 como "/assets/images/x.png"; pode ser null.
     */
    public MarkdownRenderer(File repoRoot) {
        this.repoRoot = repoRoot;
    }

    /** Largura máxima das imagens na pré-visualização (imagens maiores são reduzidas). */
    public void setMaxImageWidth(int width) {
        this.maxImageWidth = Math.max(100, width);
    }

    public synchronized String render(String markdown) {
        linkDefs = new HashMap<String, String[]>();
        footnoteDefs = new LinkedHashMap<String, String>();
        footnoteOrder = new ArrayList<String>();

        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = extractDefinitions(Arrays.asList(text.split("\n", -1)));

        StringBuilder html = new StringBuilder("<html><head></head><body>");
        html.append(renderBlocks(lines, false));
        if (!footnoteOrder.isEmpty()) {
            html.append("<hr><ol class=\"footnotes\">");
            // Laço por índice: notas citadas dentro de outras notas entram no fim da lista.
            for (int k = 0; k < footnoteOrder.size(); k++) {
                String id = footnoteOrder.get(k);
                html.append("<li><a name=\"fn-").append(escapeAttr(id)).append("\"></a>")
                        .append(inline(footnoteDefs.get(id))).append("</li>");
            }
            html.append("</ol>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------- definições

    private List<String> extractDefinitions(List<String> lines) {
        List<String> result = new ArrayList<String>();
        boolean inFence = false;
        String fence = null;
        for (String line : lines) {
            Matcher f = FENCE.matcher(line);
            if (f.matches()) {
                if (!inFence) {
                    inFence = true;
                    fence = f.group(1);
                } else if (line.trim().startsWith(fence) && line.trim().replace(fence.substring(0, 1), "").isEmpty()) {
                    inFence = false;
                }
                result.add(line);
                continue;
            }
            if (!inFence) {
                Matcher fn = FOOTNOTE_DEF.matcher(line);
                if (fn.matches()) {
                    footnoteDefs.put(fn.group(1), fn.group(2));
                    continue;
                }
                Matcher ld = LINK_DEF.matcher(line);
                if (ld.matches()) {
                    linkDefs.put(normalizeRef(ld.group(1)), new String[]{ld.group(2), ld.group(3)});
                    continue;
                }
            }
            result.add(line);
        }
        return result;
    }

    private static String normalizeRef(String ref) {
        return ref.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ----------------------------------------------------------------- blocos

    private String renderBlocks(List<String> lines, boolean tight) {
        StringBuilder out = new StringBuilder();
        int n = lines.size();
        int i = 0;
        while (i < n) {
            String line = expandTabs(lines.get(i));
            if (line.trim().isEmpty() || KRAMDOWN_ATTR.matcher(line).matches()) {
                i++;
                continue;
            }

            Matcher m = FENCE.matcher(line);
            if (m.matches()) {
                String fence = m.group(1);
                String lang = m.group(2);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n) {
                    String l = lines.get(i);
                    String t = l.trim();
                    if (t.startsWith(fence) && t.replace(fence.substring(0, 1), "").isEmpty()) {
                        i++;
                        break;
                    }
                    code.append(l).append('\n');
                    i++;
                }
                out.append(codeBlock(code.toString(), lang));
                continue;
            }

            m = HEADING.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                String content = m.group(2) == null ? "" : m.group(2);
                out.append("<h").append(level).append('>').append(inline(content))
                        .append("</h").append(level).append('>');
                i++;
                continue;
            }

            if (HR.matcher(line).matches()) {
                out.append("<hr>");
                i++;
                continue;
            }

            if (line.startsWith("    ")) {
                StringBuilder code = new StringBuilder();
                while (i < n && (expandTabs(lines.get(i)).startsWith("    ") || lines.get(i).trim().isEmpty())) {
                    String l = expandTabs(lines.get(i));
                    code.append(l.length() >= 4 ? l.substring(4) : "").append('\n');
                    i++;
                }
                out.append(codeBlock(code.toString().replaceAll("\n+$", "\n"), ""));
                continue;
            }

            if (QUOTE.matcher(line).matches()) {
                List<String> inner = new ArrayList<String>();
                while (i < n && !lines.get(i).trim().isEmpty()) {
                    Matcher q = QUOTE.matcher(lines.get(i));
                    inner.add(q.matches() ? q.group(1) : lines.get(i));
                    i++;
                }
                out.append("<blockquote>").append(renderBlocks(inner, false)).append("</blockquote>");
                continue;
            }

            if (isTableStart(lines, i)) {
                i = renderTable(lines, i, out);
                continue;
            }

            if (LIST_ITEM.matcher(line).matches()) {
                i = renderList(lines, i, out);
                continue;
            }

            if (HTML_BLOCK.matcher(line).matches()) {
                while (i < n && !lines.get(i).trim().isEmpty()) {
                    out.append(lines.get(i)).append('\n');
                    i++;
                }
                continue;
            }

            // Parágrafo (e títulos no estilo setext)
            StringBuilder para = new StringBuilder(line.trim());
            i++;
            int headingLevel = 0;
            while (i < n) {
                String l = expandTabs(lines.get(i));
                if (SETEXT_H1.matcher(l).matches()) {
                    headingLevel = 1;
                    i++;
                    break;
                }
                if (SETEXT_H2.matcher(l).matches() && !l.trim().isEmpty()) {
                    headingLevel = 2;
                    i++;
                    break;
                }
                if (l.trim().isEmpty() || interruptsParagraph(lines, i)) {
                    break;
                }
                para.append('\n').append(l.replaceAll("^\\s+", ""));
                i++;
            }
            if (headingLevel > 0) {
                out.append("<h").append(headingLevel).append('>').append(inline(para.toString()))
                        .append("</h").append(headingLevel).append('>');
            } else if (tight) {
                out.append(inline(para.toString()));
            } else {
                out.append("<p>").append(inline(para.toString())).append("</p>");
            }
        }
        return out.toString();
    }

    private boolean interruptsParagraph(List<String> lines, int i) {
        String l = expandTabs(lines.get(i));
        if (FENCE.matcher(l).matches() || HEADING.matcher(l).matches() || HR.matcher(l).matches()
                || QUOTE.matcher(l).matches() || HTML_BLOCK.matcher(l).matches() || isTableStart(lines, i)) {
            return true;
        }
        Matcher m = LIST_ITEM.matcher(l);
        return m.matches() && m.group(3) != null && (!Character.isDigit(m.group(2).charAt(0))
                || m.group(2).startsWith("1"));
    }

    private String codeBlock(String code, String lang) {
        StringBuilder sb = new StringBuilder();
        if (lang != null && !lang.isEmpty()) {
            sb.append("<div class=\"lang\">").append(escapeHtml(lang)).append("</div>");
        }
        String c = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        sb.append("<pre><code>").append(escapeHtml(c)).append("</code></pre>");
        return sb.toString();
    }

    // ----------------------------------------------------------------- listas

    private int renderList(List<String> lines, int start, StringBuilder out) {
        Matcher first = LIST_ITEM.matcher(expandTabs(lines.get(start)));
        first.matches();
        int baseIndent = first.group(1).length();
        boolean ordered = Character.isDigit(first.group(2).charAt(0));
        int startNumber = ordered ? Integer.parseInt(first.group(2).substring(0, first.group(2).length() - 1)) : 1;

        List<List<String>> items = new ArrayList<List<String>>();
        List<String> current = null;
        int contentIndent = baseIndent + 2;
        boolean loose = false;
        int n = lines.size();
        int i = start;
        while (i < n) {
            String line = expandTabs(lines.get(i));
            if (line.trim().isEmpty()) {
                int j = i + 1;
                while (j < n && lines.get(j).trim().isEmpty()) {
                    j++;
                }
                if (j >= n || current == null) {
                    break;
                }
                String next = expandTabs(lines.get(j));
                Matcher nm = LIST_ITEM.matcher(next);
                boolean sibling = nm.matches() && nm.group(1).length() < baseIndent + 2
                        && Character.isDigit(nm.group(2).charAt(0)) == ordered;
                if (indentOf(next) >= contentIndent) {
                    current.add("");
                    i++;
                    continue;
                }
                if (sibling) {
                    loose = true;
                    i = j;
                    continue;
                }
                break;
            }
            Matcher m = LIST_ITEM.matcher(line);
            if (m.matches() && m.group(1).length() < baseIndent + 2) {
                if (Character.isDigit(m.group(2).charAt(0)) != ordered) {
                    break;
                }
                current = new ArrayList<String>();
                items.add(current);
                current.add(m.group(3) == null ? "" : m.group(3));
                contentIndent = m.group(1).length() + m.group(2).length() + 1;
                i++;
                continue;
            }
            if (current != null && (indentOf(line) >= 2 || !interruptsParagraph(lines, i))) {
                int strip = Math.min(indentOf(line), contentIndent);
                current.add(line.substring(strip));
                i++;
                continue;
            }
            break;
        }

        String tag = ordered ? "ol" : "ul";
        out.append('<').append(tag);
        if (ordered && startNumber != 1) {
            out.append(" start=\"").append(startNumber).append('"');
        }
        out.append('>');
        for (List<String> item : items) {
            out.append("<li>");
            List<String> content = new ArrayList<String>(item);
            String firstLine = content.get(0);
            String lower = firstLine.toLowerCase(Locale.ROOT);
            if (lower.startsWith("[ ] ") || lower.startsWith("[x] ") || lower.equals("[ ]") || lower.equals("[x]")) {
                boolean checked = lower.startsWith("[x]");
                out.append(checked ? "<input type=\"checkbox\" checked> " : "<input type=\"checkbox\"> ");
                content.set(0, firstLine.length() > 4 ? firstLine.substring(4) : "");
            }
            boolean itemLoose = loose || content.contains("");
            out.append(renderBlocks(content, !itemLoose));
            out.append("</li>");
        }
        out.append("</").append(tag).append('>');
        return i;
    }

    private static int indentOf(String line) {
        int k = 0;
        while (k < line.length() && line.charAt(k) == ' ') {
            k++;
        }
        return k;
    }

    private static String expandTabs(String line) {
        if (line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '\t') {
                do {
                    sb.append(' ');
                } while (sb.length() % 4 != 0);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- tabelas

    private static boolean isTableStart(List<String> lines, int i) {
        return i + 1 < lines.size() && lines.get(i).contains("|")
                && TABLE_SEP.matcher(lines.get(i + 1)).matches()
                && lines.get(i + 1).contains("-")
                && (lines.get(i + 1).contains("|") || splitRow(lines.get(i)).size() > 1);
    }

    private int renderTable(List<String> lines, int start, StringBuilder out) {
        List<String> header = splitRow(lines.get(start));
        List<String> sep = splitRow(lines.get(start + 1));
        String[] aligns = new String[header.size()];
        for (int c = 0; c < aligns.length; c++) {
            String s = c < sep.size() ? sep.get(c).trim() : "";
            boolean left = s.startsWith(":");
            boolean right = s.endsWith(":");
            aligns[c] = left && right ? "center" : right ? "right" : left ? "left" : null;
        }
        out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"5\"><tr>");
        for (int c = 0; c < header.size(); c++) {
            out.append("<th").append(alignAttr(aligns[c])).append('>')
                    .append(inline(header.get(c).trim())).append("</th>");
        }
        out.append("</tr>");
        int i = start + 2;
        while (i < lines.size() && !lines.get(i).trim().isEmpty() && lines.get(i).contains("|")) {
            List<String> cells = splitRow(lines.get(i));
            out.append("<tr>");
            for (int c = 0; c < header.size(); c++) {
                String cell = c < cells.size() ? cells.get(c).trim() : "";
                out.append("<td").append(alignAttr(aligns[c])).append('>')
                        .append(cell.isEmpty() ? "&nbsp;" : inline(cell)).append("</td>");
            }
            out.append("</tr>");
            i++;
        }
        out.append("</table>");
        return i;
    }

    private static String alignAttr(String align) {
        return align == null ? "" : " align=\"" + align + "\"";
    }

    private static List<String> splitRow(String line) {
        String s = line.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|") && !s.endsWith("\\|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean inCode = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length() && s.charAt(k + 1) == '|') {
                cell.append('|');
                k++;
            } else if (c == '`') {
                inCode = !inCode;
                cell.append(c);
            } else if (c == '|' && !inCode) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    // ----------------------------------------------------------------- inline

    private String inline(String text) {
        List<String> outerSlots = slots;
        slots = new ArrayList<String>();
        try {
            return inlineInternal(text);
        } finally {
            slots = outerSlots;
        }
    }

    private String inlineInternal(String text) {
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == '\n') {
                    sb.append(slot("<br>"));
                    i += 2;
                    continue;
                }
                if ("\\`*_{}[]()#+-.!|~<>\"'".indexOf(next) >= 0) {
                    sb.append(slot(escapeHtml(String.valueOf(next))));
                    i += 2;
                    continue;
                }
            }
            if (c == '`') {
                int run = 0;
                while (i + run < n && text.charAt(i + run) == '`') {
                    run++;
                }
                String ticks = text.substring(i, i + run);
                int close = findClosingTicks(text, i + run, ticks);
                if (close >= 0) {
                    String code = text.substring(i + run, close).replace('\n', ' ');
                    if (code.length() > 1 && code.startsWith(" ") && code.endsWith(" ")) {
                        code = code.substring(1, code.length() - 1);
                    }
                    sb.append(slot("<code>" + escapeHtml(code) + "</code>"));
                    i = close + run;
                    continue;
                }
                sb.append(ticks);
                i += run;
                continue;
            }
            if (c == '!' && i + 1 < n && text.charAt(i + 1) == '[') {
                int end = parseLink(text, i + 1, true, sb);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '[') {
                if (i + 1 < n && text.charAt(i + 1) == '^') {
                    int close = text.indexOf(']', i);
                    if (close > 0) {
                        String id = text.substring(i + 2, close);
                        if (footnoteDefs.containsKey(id)) {
                            if (!footnoteOrder.contains(id)) {
                                footnoteOrder.add(id);
                            }
                            int number = footnoteOrder.indexOf(id) + 1;
                            sb.append(slot("<sup><a href=\"#fn-" + escapeAttr(id) + "\">" + number + "</a></sup>"));
                            i = close + 1;
                            continue;
                        }
                    }
                }
                int end = parseLink(text, i, false, sb);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '<') {
                String rest = text.substring(i);
                Matcher auto = AUTOLINK.matcher(rest);
                if (auto.find()) {
                    String url = auto.group(1);
                    sb.append(slot("<a href=\"" + escapeAttr(url) + "\">" + escapeHtml(url) + "</a>"));
                    i += auto.end();
                    continue;
                }
                Matcher tag = INLINE_HTML.matcher(rest);
                if (tag.find()) {
                    sb.append(slot(tag.group()));
                    i += tag.end();
                    continue;
                }
            }
            sb.append(c);
            i++;
        }

        String s = escapeHtml(sb.toString());
        s = s.replaceAll(" {2,}\n", "<br>\n");
        s = s.replaceAll("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*", "<b><i>$1</i></b>");
        s = s.replaceAll("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])", "<b>$1</b>");
        s = s.replaceAll("\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*", "<i>$1</i>");
        s = s.replaceAll("(?<![\\w])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\w])", "<i>$1</i>");
        s = s.replaceAll("~~(?=\\S)(.+?)(?<=\\S)~~", "<strike>$1</strike>");
        Matcher bare = BARE_URL.matcher(s);
        StringBuffer linked = new StringBuffer();
        while (bare.find()) {
            String url = bare.group(1);
            bare.appendReplacement(linked, Matcher.quoteReplacement("<a href=\"" + url + "\">" + url + "</a>"));
        }
        bare.appendTail(linked);
        return restoreSlots(linked.toString());
    }

    private static int findClosingTicks(String text, int from, String ticks) {
        int k = from;
        while (k < text.length()) {
            int idx = text.indexOf(ticks, k);
            if (idx < 0) {
                return -1;
            }
            int after = idx + ticks.length();
            boolean longer = after < text.length() && text.charAt(after) == '`';
            boolean preceded = idx > 0 && text.charAt(idx - 1) == '`' && idx - 1 >= from;
            if (!longer && !preceded) {
                return idx;
            }
            k = after;
            while (k < text.length() && text.charAt(k) == '`') {
                k++;
            }
        }
        return -1;
    }

    /**
     * Tenta interpretar um link/imagem começando no '[' em {@code open}.
     * Em caso de sucesso, escreve o placeholder em {@code sb} e devolve a posição seguinte.
     */
    private int parseLink(String text, int open, boolean image, StringBuilder sb) {
        int close = findMatching(text, open, '[', ']');
        if (close < 0) {
            return -1;
        }
        String label = text.substring(open + 1, close);
        String url = null;
        String title = null;
        int end;
        if (close + 1 < text.length() && text.charAt(close + 1) == '(') {
            int paren = findMatching(text, close + 1, '(', ')');
            if (paren < 0) {
                return -1;
            }
            String dest = compactLiquid(text.substring(close + 2, paren).trim());
            Matcher dm = Pattern.compile("^<?([^\\s>]*)>?(?:\\s+[\"'(](.*)[\"')])?$", Pattern.DOTALL).matcher(dest);
            if (!dm.matches()) {
                return -1;
            }
            url = dm.group(1);
            title = dm.group(2);
            end = paren + 1;
        } else {
            String ref = label;
            end = close + 1;
            if (close + 1 < text.length() && text.charAt(close + 1) == '[') {
                int refClose = text.indexOf(']', close + 1);
                if (refClose > 0) {
                    String explicit = text.substring(close + 2, refClose);
                    if (!explicit.isEmpty()) {
                        ref = explicit;
                    }
                    end = refClose + 1;
                }
            }
            String[] def = linkDefs.get(normalizeRef(ref));
            if (def == null) {
                return -1;
            }
            url = def[0];
            title = def[1];
        }

        String html;
        if (image) {
            html = imageTag(url, label, title);
        } else {
            html = "<a href=\"" + escapeAttr(url) + "\""
                    + (title != null ? " title=\"" + escapeAttr(title) + "\"" : "")
                    + ">" + inline(label) + "</a>";
        }
        sb.append(slot(html));
        return end;
    }

    /** Remove espaços dentro de tags Liquid ({{ site.baseurl }}) para que a URL não seja quebrada. */
    private static String compactLiquid(String dest) {
        Matcher m = LIQUID_TAG.matcher(dest);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("{{" + m.group(1).replaceAll("\\s+", "") + "}}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int findMatching(String text, int open, char o, char c) {
        int depth = 0;
        for (int k = open; k < text.length(); k++) {
            char ch = text.charAt(k);
            if (ch == '\\') {
                k++;
                continue;
            }
            if (ch == '`' && o == '[') {
                int closeTick = text.indexOf('`', k + 1);
                if (closeTick > 0) {
                    k = closeTick;
                    continue;
                }
            }
            if (ch == o) {
                depth++;
            } else if (ch == c) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    private String imageTag(String url, String alt, String title) {
        String src = resolveUrl(url);
        StringBuilder sb = new StringBuilder("<img src=\"").append(escapeAttr(src)).append("\" alt=\"")
                .append(escapeAttr(alt)).append('"');
        if (title != null) {
            sb.append(" title=\"").append(escapeAttr(title)).append('"');
        }
        int[] size = localImageSize(src);
        if (size != null && size[0] > maxImageWidth) {
            int h = (int) Math.round(size[1] * (maxImageWidth / (double) size[0]));
            sb.append(" width=\"").append(maxImageWidth).append("\" height=\"").append(h).append('"');
        } else if (size != null) {
            sb.append(" width=\"").append(size[0]).append("\" height=\"").append(size[1]).append('"');
        }
        return sb.append('>').toString();
    }

    /** Resolve URLs com Liquid ({{ site.baseurl }}) e caminhos do site para arquivos locais. */
    String resolveUrl(String url) {
        String u = LIQUID_BASEURL.matcher(url).replaceAll("");
        Matcher filter = LIQUID_URL_FILTER.matcher(u);
        if (filter.find()) {
            u = filter.group(1);
        }
        if (u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || repoRoot == null) {
            return u;
        }
        String path = u.replaceAll("[?#].*$", "");
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            // mantém o caminho original
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return new File(repoRoot, path).toURI().toString();
    }

    private int[] localImageSize(String src) {
        if (!src.startsWith("file:")) {
            return null;
        }
        File file;
        try {
            file = new File(new java.net.URI(src));
        } catch (Exception e) {
            return null;
        }
        if (!file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath() + "@" + file.lastModified();
        if (imageSizeCache.containsKey(key)) {
            return imageSizeCache.get(key);
        }
        int[] size = null;
        ImageInputStream in = null;
        try {
            in = ImageIO.createImageInputStream(file);
            if (in != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in);
                        size = new int[]{reader.getWidth(0), reader.getHeight(0)};
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception ignored) {
            // formato não suportado pelo ImageIO (ex.: webp, svg)
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nada
                }
            }
        }
        imageSizeCache.put(key, size);
        return size;
    }

    private String slot(String html) {
        slots.add(html);
        return "\u0001" + (slots.size() - 1) + "\u0002";
    }

    private String restoreSlots(String s) {
        String result = s;
        // Placeholders podem conter outros placeholders; repete até estabilizar.
        for (int pass = 0; pass < 5 && result.indexOf('\u0001') >= 0; pass++) {
            Matcher m = PLACEHOLDER.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                String replacement = idx < slots.size() ? slots.get(idx) : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    // --------------------------------------------------------------- escapes

    public static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeAttr(String s) {
        return escapeHtml(s == null ? "" : s).replace("\"", "&quot;");
    }
}
