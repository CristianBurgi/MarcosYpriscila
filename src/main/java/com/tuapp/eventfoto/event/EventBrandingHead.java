package com.tuapp.eventfoto.event;

/**
 * Lo que la personalización (Fase 9.5) agrega antes de {@code </head>}: la hoja /css/event-branding.css y las
 * variables de la paleta derivada de un color, con la imagen de fondo o el degradado de la paleta. Lo usan las
 * páginas de un evento real (GuestPageController) y las de la demo (Fase 9.7-B), con la misma salida.
 */
public final class EventBrandingHead {

    private EventBrandingHead() {
    }

    /**
     * @param color    hex #RRGGBB ya validado
     * @param imageUrl URL pública de la imagen de fondo (generada por nosotros, nunca un input), o null
     */
    public static String of(String color, String imageUrl) {
        EventPalette palette = EventPalette.derive(color);
        StringBuilder vars = new StringBuilder(":root{")
                .append("--brand-primary:").append(palette.primary()).append(';')
                .append("--brand-primary-deep:").append(palette.primaryDeep()).append(';')
                .append("--brand-primary-light:").append(palette.primaryLight()).append(';')
                .append("--brand-secondary:").append(palette.secondary()).append(';')
                .append("--brand-on-primary:").append(palette.textOnPrimary()).append(';')
                .append("--brand-on-secondary:").append(palette.textOnSecondary()).append(';')
                .append("--gold:").append(palette.primaryLight()).append(';')
                .append("--gold-soft:").append(palette.primaryLight()).append(';')
                .append("--accent-rgb:").append(EventPalette.rgbTriplet(palette.primaryLight())).append(';');
        if (imageUrl != null) {
            String url = imageUrl.replace("\\", "\\\\").replace("\"", "\\\"");
            vars.append("--brand-bg:url(\"").append(url).append("\") center/cover no-repeat;");
        } else {
            vars.append("--brand-bg:linear-gradient(165deg,").append(palette.primaryLight()).append(" 0%,")
                    .append(palette.primary()).append(" 55%,").append(palette.primaryDeep()).append(" 100%);");
        }
        vars.append('}');

        return "<link rel=\"stylesheet\" href=\"/css/event-branding.css?v=1\">\n"
                + "<style id=\"event-palette\">" + vars + "</style>\n";
    }

    public static String injectBeforeHeadEnd(String html, String fragment) {
        int head = html.indexOf("</head>");
        if (head < 0) {
            throw new IllegalStateException("La página no tiene </head>");
        }
        return html.substring(0, head) + fragment + html.substring(head);
    }
}
