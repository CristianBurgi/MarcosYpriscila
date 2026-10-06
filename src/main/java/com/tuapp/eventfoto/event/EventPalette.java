package com.tuapp.eventfoto.event;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Paleta de un evento derivada de UN color. Única fuente de verdad del algoritmo: la usan las páginas de
 * invitado (GuestPageController), y la van a usar las tarjetas QR (9.8) y el libro de visitas.
 *
 * Portada de {@code derivePalette} del mockup (docs/mockups/wizard-onboarding.dc.html) con dos arreglos:
 * con un color gris (saturación < 5) todos los derivados van sin saturación (si no, el mínimo de
 * saturación con matiz 0 los teñía de rosado), y {@code primaryDeep} nunca queda más claro que el primario
 * (el mínimo de luminosidad 10 aclaraba los negros). La vista previa del wizard usa la copia en JS
 * (static/js/event-palette.js); EventPaletteParityTest verifica que den lo mismo.
 */
public record EventPalette(String primary, String primaryDeep, String primaryLight, String secondary,
                           String textOnPrimary, String textOnSecondary) {

    public static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String DARK_TEXT = "#1C2321";
    private static final String LIGHT_TEXT = "#FFFFFF";
    private static final double GRAY_SATURATION = 5;

    /** {@code #rrggbb} en minúsculas, o IllegalArgumentException si no tiene exactamente ese formato. */
    public static String normalize(String hex) {
        if (hex == null || !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("El color tiene que tener el formato #rrggbb");
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    public static EventPalette derive(String hex) {
        String primary = normalize(hex);
        double[] hsl = hexToHsl(primary);
        double h = hsl[0], s = hsl[1], l = hsl[2];
        boolean gray = s < GRAY_SATURATION;

        String primaryDeep = hslToHex(h, gray ? 0 : s, Math.min(clamp(l - 20, 10, 90), l));
        String primaryLight = hslToHex(h, gray ? 0 : Math.max(s * 0.35, 10), 92);
        String secondary = hslToHex(h, gray ? 0 : Math.max(s * 0.55, 15), clamp(l + 14, 25, 75));
        return new EventPalette(primary, primaryDeep, primaryLight, secondary, readableText(primary), readableText(secondary));
    }

    /** "r, g, b" para usar dentro de {@code rgba(var(--x), α)}. */
    public static String rgbTriplet(String hex) {
        return Integer.parseInt(hex.substring(1, 3), 16) + ", " + Integer.parseInt(hex.substring(3, 5), 16)
                + ", " + Integer.parseInt(hex.substring(5, 7), 16);
    }

    static double[] hexToHsl(String hex) {
        double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0;
        double g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0;
        double b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h = 0, s = 0;
        double l = (max + min) / 2;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h /= 6;
        }
        return new double[]{h * 360, s * 100, l * 100};
    }

    static String hslToHex(double h, double s, double l) {
        h = ((h % 360) + 360) % 360;
        s = clamp(s, 0, 100) / 100;
        l = clamp(l, 0, 100) / 100;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return "#" + toHex(r + m) + toHex(g + m) + toHex(b + m);
    }

    private static String toHex(double v) {
        // Math.round de Java y de JS coinciden para valores positivos (redondean .5 hacia arriba).
        return String.format("%02x", Math.round(v * 255));
    }

    private static String readableText(String hex) {
        return hexToHsl(hex)[2] > 55 ? DARK_TEXT : LIGHT_TEXT;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
