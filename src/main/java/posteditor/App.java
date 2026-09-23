package posteditor;

import posteditor.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Ponto de entrada do editor de posts para GitHub Pages.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Mantém o look and feel padrão
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                MainFrame frame = new MainFrame(Config.load());
                frame.setVisible(true);
            }
        });
    }
}
