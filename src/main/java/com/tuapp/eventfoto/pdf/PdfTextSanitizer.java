package com.tuapp.eventfoto.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deja el texto escrito por usuarios listo para el PDF, sin ningún carácter que se vería
 * como cuadradito (tofu). Un PDF de recuerdo con cuadraditos en lugar de los emojis que
 * escribieron los invitados arruina el recuerdo.
 *
 * - Quita los caracteres que ninguna fuente registrada puede dibujar.
 * - Quita los modificadores de tono de piel: en la fuente de emojis monocromática se
 *   dibujan como un recuadro gris al lado de la mano.
 * - Quita el unidor de ancho cero (ZWJ) y los selectores de variación: PDFBox no arma
 *   secuencias compuestas, así que 👨‍👩‍👧 se dibuja como 👨👩👧 (las tres personas, sin
 *   huecos raros).
 * - Normaliza saltos de línea y conserva el resto tal cual.
 */
@Slf4j
@Component
public class PdfTextSanitizer {

    private final List<CmapLookup> cmaps = new ArrayList<>();

    public PdfTextSanitizer(PdfRenderService pdfRenderService) {
        for (byte[] font : pdfRenderService.registeredFonts()) {
            try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(font))) {
                cmaps.add(ttf.getUnicodeCmapLookup());
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer el cmap de una fuente del PDF", e);
            }
        }
    }

    public String clean(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') {
                out.appendCodePoint(cp);
            } else if (isDroppedModifier(cp) || Character.isISOControl(cp)) {
                // se descarta
            } else if (Character.isWhitespace(cp) || cp == 0x00A0) {
                out.append(' ');
            } else if (isDrawable(cp)) {
                out.appendCodePoint(cp);
            } else {
                log.debug("Carácter sin glifo en las fuentes del PDF, se omite: U+{}", Integer.toHexString(cp).toUpperCase());
            }
        });
        return out.toString().strip();
    }

    boolean isDrawable(int codePoint) {
        for (CmapLookup cmap : cmaps) {
            if (cmap.getGlyphId(codePoint) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDroppedModifier(int cp) {
        return (cp >= 0x1F3FB && cp <= 0x1F3FF)   // tonos de piel
                || cp == 0x200D                    // zero width joiner
                || (cp >= 0xFE00 && cp <= 0xFE0F)  // selectores de variación
                || cp == 0x20E3;                   // keycap combinado (1️⃣ -> 1)
    }
}
