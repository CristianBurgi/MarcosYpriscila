package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.InvalidFileContentException;
import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Validación del contenido real de una imagen subida, compartida por los caminos de subida (fotos de invitados
 * por /confirm y /upload-direct, imagen de fondo del organizador): firma binaria (magic bytes) y HEIC/HEIF ->
 * JPEG. La decisión se toma sobre los BYTES, nunca sobre la extensión ni el Content-Type declarado: un iPhone
 * puede mandar un .heic cuyo contenido ya es JPEG, y en ese caso no se convierte nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageContent {

    /** Bytes listos para guardar; {@code convertedFromHeic} = ahora son JPEG y la clave tiene que ser .jpg. */
    public record Normalized(byte[] bytes, boolean convertedFromHeic) {
    }

    private final StorageService storageService;

    /**
     * @throws InvalidFileContentException si la firma no es de JPEG, PNG, WEBP ni HEIC
     * @throws StorageException            si falla la conversión HEIC -> JPEG
     */
    public Normalized validateAndConvert(byte[] bytes, String context) {
        if (!FileSignatureValidator.isValidImageSignature(headerOf(bytes))) {
            log.warn("[{}] Rechazado: la firma binaria no corresponde a una imagen válida", context);
            throw new InvalidFileContentException(
                    "El archivo subido no corresponde a una imagen válida (JPEG, PNG, WEBP o HEIC). La subida fue rechazada.");
        }
        boolean heic = FileSignatureValidator.isHeicSignature(headerOf(bytes));
        // Diagnóstico HEIC (19/09), en debug desde la Fase 9.0: logging.level.com.tuapp.eventfoto.storage=DEBUG.
        log.debug("[HEIC-DECISION][{}] isHeicSignature={} bytes={} primeros12={} -> {}", context, heic, bytes.length,
                Arrays.toString(headerOf(bytes)), heic ? "se convierte con heif-convert" : "ya es imagen navegable, se guarda tal cual");
        if (!heic) {
            return new Normalized(bytes, false);
        }
        try {
            byte[] jpeg = storageService.convertHeicToJpeg(bytes);
            log.info("[{}] Conversión HEIC->JPEG exitosa ({} bytes -> {} bytes)", context, bytes.length, jpeg.length);
            return new Normalized(jpeg, true);
        } catch (Exception e) {
            log.error("[{}] Falló la conversión HEIC->JPEG: {}", context, e.getMessage());
            throw new StorageException("No se pudo procesar la foto HEIC subida. Por favor, intentá subirla nuevamente.", e);
        }
    }

    public static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    /**
     * Copia del JPEG sin los segmentos de metadatos APP1 (EXIF, con el GPS, y XMP) ni APP13 (IPTC). El resto
     * de los segmentos y los datos de imagen quedan byte a byte. heif-convert (libheif) copia el EXIF del HEIC
     * al JPEG, así que sin esto una foto sacada con el celular llevaría la ubicación a una URL pública.
     * Si el archivo no se puede recorrer como JPEG, se rechaza: no se publica algo que no se pudo limpiar.
     */
    public static byte[] stripJpegMetadata(byte[] jpeg) {
        if (!isJpeg(jpeg)) {
            throw new InvalidFileContentException("La imagen de fondo tiene que ser JPG, PNG o HEIC.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i + 4 <= jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                throw new InvalidFileContentException("No pudimos procesar la imagen. Probá con otra foto.");
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA) { // Start of Scan: desde acá son datos de imagen hasta el final
                out.write(jpeg, i, jpeg.length - i);
                return out.toByteArray();
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > jpeg.length) {
                throw new InvalidFileContentException("No pudimos procesar la imagen. Probá con otra foto.");
            }
            if (marker != 0xE1 && marker != 0xED) {
                out.write(jpeg, i, 2 + length);
            }
            i += 2 + length;
        }
        throw new InvalidFileContentException("No pudimos procesar la imagen. Probá con otra foto.");
    }

    private static byte[] headerOf(byte[] bytes) {
        return bytes.length > 12 ? Arrays.copyOf(bytes, 12) : bytes;
    }
}
