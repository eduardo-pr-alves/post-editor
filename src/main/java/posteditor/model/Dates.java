package posteditor.model;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Conversão entre datas e o formato usado no front matter do Jekyll.
 */
public final class Dates {

    private static final String JEKYLL = "yyyy-MM-dd HH:mm:ss Z";
    private static final String[] ACCEPTED = {JEKYLL, "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};

    private Dates() {
    }

    /** Formata no padrão do Jekyll, ex.: 2026-09-24 14:30:00 -0300. */
    public static String toJekyll(Date date) {
        return new SimpleDateFormat(JEKYLL).format(date);
    }

    /** Formato amigável para a interface, ex.: 24/09/2026 14:30. */
    public static String toDisplay(Date date) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm").format(date);
    }

    /** Interpreta uma data do front matter; devolve null se inválida. */
    public static Date parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        for (String pattern : ACCEPTED) {
            SimpleDateFormat format = new SimpleDateFormat(pattern);
            format.setLenient(false);
            try {
                return format.parse(t);
            } catch (ParseException ignored) {
                // tenta o próximo formato
            }
        }
        return null;
    }
}
