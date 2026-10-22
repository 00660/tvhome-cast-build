package com.gathin.tvhome;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * A festive skin set by a theme's config.json: {@code festival}, {@code festival_until}, {@code greeting}
 * and {@code accent_color}. While it is active the pills get festive icons (a diya for the network, a bell
 * for Inputs, a marigold for Settings), the greeting is replaced and the focus glow takes the accent colour.
 * It switches itself off after {@code festival_until}, so nobody has to remember to undo it.
 */
final class Festival {

    static final Festival NONE = new Festival("", null, "", 0);

    /** Warm diya gold, the accent when a theme names a festival but no colour. */
    static final int DEFAULT_ACCENT = 0xFFFFB23C;

    /** Lower-case festival name, or "" for none. */
    final String name;
    /** Last day the skin shows, inclusive; null for no end date. */
    final Date until;
    /** Text that replaces the time-of-day greeting; "" uses the festival's own default. */
    final String greeting;
    /** Focus glow colour as 0xAARRGGBB, or 0 for {@link #DEFAULT_ACCENT}. */
    final int accent;

    private Festival(String name, Date until, String greeting, int accent) {
        this.name = name;
        this.until = until;
        this.greeting = greeting;
        this.accent = accent;
    }

    /**
     * Builds the skin from raw config values. Anything malformed turns the skin off rather than leaving a
     * festival on the screen forever: an unreadable end date or an empty name gives {@link #NONE}.
     */
    static Festival parse(String name, String until, String greeting, String accent) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 40) return NONE;
        Date end = null;
        if (until != null && !until.trim().isEmpty()) {
            end = parseDate(until.trim());
            if (end == null) return NONE;
        }
        String g = greeting == null ? "" : Cards.cleanLabel(greeting, "");
        return new Festival(n, end, g, parseColor(accent));
    }

    /** Whether the skin shows today. */
    boolean active(Date today) {
        return !name.isEmpty() && (until == null || !today.after(until));
    }

    static Date today() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Date parseDate(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        return parsed != null && position.getIndex() == value.length() ? parsed : null;
    }

    /** The greeting to show while active, or "" to keep the normal time-of-day greeting. */
    String greetingText() {
        if (!greeting.isEmpty()) return greeting;
        switch (name) {
            case "dussehra":
                return "Happy Dussehra";
            case "diwali":
                return "Happy Diwali";
            default:
                return "";
        }
    }

    int accentColor() {
        return accent != 0 ? accent : DEFAULT_ACCENT;
    }

    /** {@code #RRGGBB} to an opaque colour, anything else to 0. */
    static int parseColor(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (!t.matches("#[0-9A-Fa-f]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(t.substring(1), 16);
    }
}
