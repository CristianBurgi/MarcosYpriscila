package com.tuapp.eventfoto.kit;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.kit.VisibilityKitService.Format;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.8: tarjetas y cartel con una paleta clara y una oscura, un nombre con acentos, ñ y emoji, y uno de 60
 * caracteres. Se verifica sobre el PDF real: el texto está, ningún glifo sale de su tarjeta y el QR de cada tarjeta
 * (renderizada a 150 dpi, como una foto mala) decodifica al menú de invitados.
 *
 * Deja target/kit-*.pdf y .png para revisión visual.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.base-url=https://event-foto.up.railway.app")
class VisibilityKitPdfTest {

    static final String SIXTY = "Casamiento de Maximiliano y Josefina en la estancia La Paz 2026";
    static final String SLUG = "casamiento-de-maximiliano-y-josefina-en-la-estancia-la-paz-20-x7k2m9pq";
    private static final float MM = 72f / 25.4f;

    @Autowired
    private VisibilityKitService service;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"CARDS, #f5f5f0, clara", "CARDS, #2e4374, oscura", "POSTER, #f5f5f0, clara", "POSTER, #2e4374, oscura"})
    @DisplayName("Tarjetas y cartel: texto completo, nada fuera de la tarjeta y QR legible al menú de invitados")
    void kitRendersAndScans(Format format, String color, String variant) throws Exception {
        for (String name : new String[]{"Boda de Ñandú & Inés 🎉", SIXTY.substring(0, 60)}) {
            assertThat(name.codePointCount(0, name.length())).isLessThanOrEqualTo(60);
            Event event = Event.builder().name(name).slug(SLUG).backgroundColor(color).isActive(true).origin(EventOrigin.PAID).build();
            byte[] pdf = service.render(event, format);
            String file = "target/kit-" + format.name().toLowerCase() + "-" + variant + (name.startsWith("Boda") ? "" : "-largo");
            Files.write(Path.of(file + ".pdf"), pdf);

            try (PDDocument doc = Loader.loadPDF(pdf)) {
                assertThat(doc.getNumberOfPages()).as("una sola hoja").isEqualTo(1);
                String text = new PDFTextStripper().getText(doc);
                String compact = text.replaceAll("\\s+", "");
                assertThat(compact).contains(name.replaceAll("\\s+", ""));
                assertThat(text).contains("📸", "Escaneá el código con la cámara de tu celular", "EventFoto")
                        .doesNotContain("�").doesNotContain("#").doesNotContain("plataforma");
                assertThat(text.split("📸", -1)).as("una llamada por tarjeta").hasSize(format == Format.CARDS ? 5 : 2);
                assertThat(glyphsOutsideTheirCard(doc, format)).isEmpty();

                BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 150);
                ImageIO.write(page, "png", new File(file + ".png"));
                int cards = format == Format.CARDS ? 4 : 1;
                for (int i = 0; i < cards; i++) {
                    int w = page.getWidth() / (cards == 4 ? 2 : 1), h = page.getHeight() / (cards == 4 ? 2 : 1);
                    BufferedImage card = page.getSubimage(i % 2 * w, i / 2 * h, w, h);
                    String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(card))),
                            Map.of()).getText();
                    assertThat(decoded).as("QR de la tarjeta " + i).isEqualTo("https://event-foto.up.railway.app/e/" + SLUG);
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("QR para imprimir: nivel Q (v8, 49 módulos con la URL de producción y un slug de 60 + sufijo), 40 px por módulo, sin margen")
    void printQrIsLevelQWithWholePixelsPerModule() throws Exception {
        var qr = qrCodeService.generatePrintPng("https://event-foto.up.railway.app/e/" + SLUG, VisibilityKitService.PIXELS_PER_MODULE);
        assertThat(qr.modules()).isEqualTo(49); // con M o con H el tamaño es otro
        BufferedImage png = ImageIO.read(new java.io.ByteArrayInputStream(qr.png()));
        assertThat(png.getWidth()).isEqualTo(49 * 40);
        assertThat(png.getRGB(0, 0) & 0xffffff).as("margen 0: el primer píxel es el finder pattern").isZero();
    }

    @Autowired
    private com.tuapp.eventfoto.qr.QrCodeService qrCodeService;

    /** Cada glifo tiene que quedar dentro de su tarjeta (105 x 148 mm, o el A4 entero), con 4 mm de margen. */
    private static List<String> glyphsOutsideTheirCard(PDDocument doc, Format format) throws Exception {
        float cardW = 105 * MM * (format == Format.CARDS ? 1 : 2), cardH = 148 * MM * (format == Format.CARDS ? 1 : 2), margin = 4 * MM;
        List<String> outside = new ArrayList<>();
        new PDFTextStripper() {
            @Override
            protected void writeString(String s, List<TextPosition> positions) {
                for (TextPosition p : positions) {
                    float x = p.getXDirAdj(), y = p.getYDirAdj();
                    float left = (float) Math.floor(x / cardW) * cardW, top = (float) Math.floor(y / cardH) * cardH;
                    if (x < left + margin || x + p.getWidthDirAdj() > left + cardW - margin
                            || y - p.getHeightDir() < top + margin || y > top + cardH - margin) {
                        outside.add("'" + p.getUnicode() + "' en x=" + x + " y=" + y);
                    }
                }
            }
        }.getText(doc);
        return outside;
    }
}
