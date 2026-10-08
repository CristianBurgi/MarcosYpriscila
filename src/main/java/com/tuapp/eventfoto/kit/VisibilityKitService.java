package com.tuapp.eventfoto.kit;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventPalette;
import com.tuapp.eventfoto.event.GuestPageController;
import com.tuapp.eventfoto.pdf.PdfRenderService;
import com.tuapp.eventfoto.pdf.PdfTextSanitizer;
import com.tuapp.eventfoto.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;

/**
 * Kit de visibilidad (Fase 9.8): tarjetas para las mesas (A4 con 4 tarjetas A6) y cartel A4 para la entrada. Una sola
 * plantilla (pdf/kit-visibilidad) con el mismo contenido en dos tamaños.
 *
 * El QR apunta al menú de invitados, va negro sobre blanco con una quiet zone de 4 módulos (el padding del recuadro
 * blanco) y en PNG con un número entero de píxeles por módulo. El texto va sobre el primario con textOnPrimary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisibilityKitService {

    /** 40 px por módulo: con el QR de una URL real (v8, 49 módulos) son ~710 dpi en la tarjeta y ~355 en el cartel. */
    static final int PIXELS_PER_MODULE = 40;
    static final int QUIET_ZONE_MODULES = 4;

    private final PdfRenderService pdfRenderService;
    private final PdfTextSanitizer sanitizer;
    private final QrCodeService qrCodeService;
    private final AppUrls appUrls;

    /** El A4 mide el doble de un A6 de cada lado: el cartel es la tarjeta con todas las medidas por 2. */
    public enum Format {
        CARDS("tarjetas-para-las-mesas.pdf", 4, 1),
        POSTER("cartel-para-la-entrada.pdf", 1, 2);

        public final String filename;
        final int copies;
        final int scale;

        Format(String filename, int copies, int scale) {
            this.filename = filename;
            this.copies = copies;
            this.scale = scale;
        }
    }

    /** Lado del QR en la tarjeta (en el cartel, 140 mm). */
    static final int CARD_QR_MM = 70;

    public byte[] render(Event event, Format format) {
        String name = sanitizer.clean(event.getName());
        QrCodeService.PrintQr qr = qrCodeService.generatePrintPng(appUrls.guestMenuUrl(event.getSlug()), PIXELS_PER_MODULE);
        String color = event.getBackgroundColor() != null ? event.getBackgroundColor() : GuestPageController.DEFAULT_COLOR;
        int length = name.codePointCount(0, name.length());
        // El bloque del nombre tiene alto fijo y oculta lo que sobre: un nombre larguísimo nunca empuja el QR.
        int nameSize = (length <= 30 ? 18 : length <= 60 ? 14 : 11) * format.scale;
        int qrMm = CARD_QR_MM * format.scale;

        byte[] pdf = pdfRenderService.render("pdf/kit-visibilidad", Map.of(
                "k", format.scale,
                "copies", format.copies,
                "eventName", name,
                "nameSizePt", nameSize,
                "palette", EventPalette.derive(color),
                "qrSrc", "data:image/png;base64," + Base64.getEncoder().encodeToString(qr.png()),
                "qrMm", qrMm,
                "quietMm", QUIET_ZONE_MODULES * (double) qrMm / qr.modules()));
        log.info("Kit de visibilidad ({}) generado para '{}': QR de {} módulos en {} mm, {} bytes",
                format, event.getSlug(), qr.modules(), qrMm, pdf.length);
        return pdf;
    }
}
