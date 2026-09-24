package com.tuapp.eventfoto.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FSFontUseCase;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Servicio genérico de PDF: plantilla Thymeleaf (templates/pdf/...) -> HTML -> PDF con
 * openhtmltopdf. No sabe nada del contenido: lo usa el libro de visitas y se va a reusar
 * para imprimir tarjetas QR.
 *
 * Fuentes: se embeben las de la app (Playfair Display, Cormorant Garamond) más dos de
 * respaldo que openhtmltopdf usa carácter por carácter cuando una fuente del documento no
 * tiene el glifo: Noto Serif (letras de otros alfabetos) y Noto Emoji (monocromática).
 * PDFBox no puede dibujar emojis a color. Ver resources/pdf/fonts/README.md.
 *
 * El texto que viene de usuarios tiene que pasar por {@link PdfTextSanitizer} antes de
 * llegar a la plantilla: garantiza que ningún carácter quede como cuadradito.
 */
@Slf4j
@Service
public class PdfRenderService {

    record FontSpec(String file, String family, int weight, FontStyle style, boolean fallback) {
    }

    static final List<FontSpec> FONTS = List.of(
            new FontSpec("PlayfairDisplay-Regular.ttf", "Playfair Display", 400, FontStyle.NORMAL, false),
            new FontSpec("PlayfairDisplay-SemiBold.ttf", "Playfair Display", 600, FontStyle.NORMAL, false),
            new FontSpec("PlayfairDisplay-Italic.ttf", "Playfair Display", 400, FontStyle.ITALIC, false),
            new FontSpec("CormorantGaramond-Medium.ttf", "Cormorant Garamond", 400, FontStyle.NORMAL, false),
            new FontSpec("CormorantGaramond-Bold.ttf", "Cormorant Garamond", 700, FontStyle.NORMAL, false),
            new FontSpec("CormorantGaramond-MediumItalic.ttf", "Cormorant Garamond", 400, FontStyle.ITALIC, false),
            new FontSpec("NotoSerif-Regular.ttf", "Noto Serif", 400, FontStyle.NORMAL, true),
            new FontSpec("NotoEmoji-Regular.ttf", "Noto Emoji", 400, FontStyle.NORMAL, true)
    );

    private static final String FONT_DIR = "pdf/fonts/";

    private final TemplateEngine templateEngine;
    private final Map<FontSpec, byte[]> fontBytes = new LinkedHashMap<>();

    public PdfRenderService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        // Las fuentes se leen una sola vez: son ~4 MB y cada render las necesita todas.
        for (FontSpec font : FONTS) {
            fontBytes.put(font, readClasspath(FONT_DIR + font.file()));
        }
    }

    /** Bytes de las fuentes registradas, en orden (los usa el sanitizador para saber qué glifos existen). */
    public List<byte[]> registeredFonts() {
        return List.copyOf(fontBytes.values());
    }

    /**
     * @param template nombre de la plantilla relativo a templates/, sin extensión
     *                 (ej. "pdf/libro-de-visitas")
     */
    public byte[] render(String template, Map<String, Object> model) {
        Context context = new Context();
        context.setVariables(model);
        String html = templateEngine.process(template, context);

        // La salida de Thymeleaf en modo HTML no es XML estricto (<meta> sin cerrar, etc.):
        // jsoup la parsea como HTML5 y la pasa a DOM W3C, que es lo que consume openhtmltopdf.
        org.w3c.dom.Document document = new W3CDom().fromJsoup(Jsoup.parse(html));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withProducer("EventFoto");
            fontBytes.forEach((font, bytes) -> {
                Set<FSFontUseCase> useCases = font.fallback()
                        ? EnumSet.of(FSFontUseCase.DOCUMENT, FSFontUseCase.FALLBACK_FINAL)
                        : EnumSet.of(FSFontUseCase.DOCUMENT);
                builder.useFont(() -> new ByteArrayInputStream(bytes), font.family(), font.weight(), font.style(), true, useCases);
            });
            builder.withW3cDocument(document, "/");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF '" + template + "'", e);
        }
    }

    private static byte[] readClasspath(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Falta la fuente del PDF en el classpath: " + path, e);
        }
    }
}
