package posteditor.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Ícone do aplicativo (janela e bandeja), desenhado em código para não
 * depender de arquivos de imagem.
 */
final class AppIcon {

    private AppIcon() {
    }

    static Image create(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = Math.max(4, size / 4);
        g.setColor(new Color(9, 105, 218));
        g.fillRoundRect(0, 0, size, size, arc, arc);

        // Folha com linhas de texto
        g.setColor(Color.WHITE);
        int m = size / 5;
        g.fillRoundRect(m, m - size / 16, size - 2 * m, size - 2 * m + size / 8, arc / 2, arc / 2);
        g.setColor(new Color(9, 105, 218));
        g.setStroke(new BasicStroke(Math.max(1f, size / 14f)));
        int left = m + size / 8;
        int right = size - m - size / 8;
        for (int i = 0; i < 3; i++) {
            int y = m + size / 7 + i * (size / 6);
            g.drawLine(left, y, i == 2 ? (left + right) / 2 : right, y);
        }
        g.dispose();
        return img;
    }

    static List<Image> allSizes() {
        List<Image> images = new ArrayList<Image>();
        for (int size : new int[]{16, 24, 32, 48, 64}) {
            images.add(create(size));
        }
        return images;
    }
}
