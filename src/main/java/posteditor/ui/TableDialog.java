package posteditor.ui;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera tabelas Markdown, a partir de um tamanho escolhido ou de dados
 * copiados de uma planilha (separados por tab, ponto e vírgula ou vírgula).
 */
final class TableDialog {

    private static final String[] ALIGNMENTS = {"Esquerda", "Centro", "Direita"};

    private TableDialog() {
    }

    /** Mostra o diálogo e devolve a tabela em Markdown, ou null se cancelado. */
    static String show(Component parent) {
        JSpinner rows = new JSpinner(new SpinnerNumberModel(3, 1, 100, 1));
        JSpinner cols = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
        JComboBox<String> align = new JComboBox<String>(ALIGNMENTS);

        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 6));
        panel.add(new JLabel("Linhas (sem o cabeçalho):"));
        panel.add(rows);
        panel.add(new JLabel("Colunas:"));
        panel.add(cols);
        panel.add(new JLabel("Alinhamento:"));
        panel.add(align);

        int option = JOptionPane.showConfirmDialog(parent, panel, "Inserir tabela",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) {
            return null;
        }
        int r = (Integer) rows.getValue();
        int c = (Integer) cols.getValue();
        List<List<String>> data = new ArrayList<List<String>>();
        List<String> header = new ArrayList<String>();
        for (int j = 1; j <= c; j++) {
            header.add("Coluna " + j);
        }
        data.add(header);
        for (int i = 0; i < r; i++) {
            List<String> row = new ArrayList<String>();
            for (int j = 0; j < c; j++) {
                row.add("   ");
            }
            data.add(row);
        }
        return build(data, align.getSelectedIndex());
    }

    /** Converte texto tabular (ex.: colado do Excel) em tabela Markdown; null se não parecer tabela. */
    static String fromDelimited(String text) {
        String[] lines = text.replace("\r", "").trim().split("\n");
        if (lines.length < 1) {
            return null;
        }
        String sep = lines[0].contains("\t") ? "\t" : lines[0].contains(";") ? ";" : lines[0].contains(",") ? "," : null;
        if (sep == null) {
            return null;
        }
        List<List<String>> data = new ArrayList<List<String>>();
        int width = 0;
        for (String line : lines) {
            List<String> row = new ArrayList<String>();
            for (String cell : line.split(java.util.regex.Pattern.quote(sep), -1)) {
                row.add(cell.trim());
            }
            width = Math.max(width, row.size());
            data.add(row);
        }
        for (List<String> row : data) {
            while (row.size() < width) {
                row.add("");
            }
        }
        return build(data, 0);
    }

    private static String build(List<List<String>> data, int alignment) {
        String sepCell = alignment == 1 ? ":---:" : alignment == 2 ? "---:" : "---";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            sb.append('|');
            for (String cell : data.get(i)) {
                sb.append(' ').append(cell.replace("|", "\\|")).append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|');
                for (int j = 0; j < data.get(0).size(); j++) {
                    sb.append(' ').append(sepCell).append(" |");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
