package posteditor.ui;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerDateModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Calendar;
import java.util.Date;

/**
 * Diálogo para escolher a data/hora de publicação automática.
 */
final class ScheduleDialog {

    /** Resultado do diálogo. */
    enum Choice { SCHEDULE, UNSCHEDULE, CANCEL }

    private Choice choice = Choice.CANCEL;
    private Date date;

    private ScheduleDialog() {
    }

    Choice getChoice() {
        return choice;
    }

    Date getDate() {
        return date;
    }

    /**
     * @param current agendamento atual do post, ou null se ainda não agendado
     */
    static ScheduleDialog show(Component parent, String postTitle, Date current) {
        ScheduleDialog result = new ScheduleDialog();
        Date initial = current != null ? current : nextHour();
        final SpinnerDateModel model = new SpinnerDateModel(initial, null, null, Calendar.MINUTE);
        JSpinner spinner = new JSpinner(model);
        spinner.setEditor(new JSpinner.DateEditor(spinner, "dd/MM/yyyy HH:mm"));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>Publicar automaticamente <b>" + escape(postTitle) + "</b> em:</html>"),
                BorderLayout.NORTH);
        panel.add(spinner, BorderLayout.CENTER);
        panel.add(new JLabel("<html><font color=\"#666666\">O Post Editor precisa estar aberto (pode ficar na "
                + "bandeja do sistema).<br>Se estiver fechado no horário, o post é publicado assim que o "
                + "aplicativo for aberto.</font></html>"), BorderLayout.SOUTH);

        Object[] options = current != null
                ? new Object[]{"Agendar", "Remover agendamento", "Cancelar"}
                : new Object[]{"Agendar", "Cancelar"};
        while (true) {
            int answer = JOptionPane.showOptionDialog(parent, panel, "Agendar publicação",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (answer == 0) {
                Date chosen = model.getDate();
                if (!chosen.after(new Date())) {
                    JOptionPane.showMessageDialog(parent, "Escolha uma data e hora no futuro.", "Agendar publicação",
                            JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                result.choice = Choice.SCHEDULE;
                result.date = chosen;
            } else if (answer == 1 && current != null) {
                result.choice = Choice.UNSCHEDULE;
            }
            return result;
        }
    }

    private static Date nextHour() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
