package com.tuapp.eventfoto.storage;

import java.nio.charset.StandardCharsets;

/**
 * Verifica la firma binaria real (magic bytes) de un archivo contra los formatos
 * de imagen permitidos por la aplicación (JPEG, PNG, WEBP, HEIC/HEIF).
 *
 * Esto complementa —no reemplaza— la validación de Content-Type declarado en
 * {@link R2StorageService#validateContentType(String)}: el Content-Type que manda
 * el cliente puede mentir, pero los primeros bytes del archivo no.
 */
public final class FileSignatureValidator {

    private FileSignatureValidator() {
    }

    /**
     * @param header al menos los primeros 12 bytes del archivo (menos bytes siempre es inválido).
     * @return true si la firma corresponde a JPEG, PNG, WEBP o HEIC/HEIF.
     */
    public static boolean isValidImageSignature(byte[] header) {
        if (header == null || header.length < 12) {
            return false;
        }
        return isJpeg(header) || isPng(header) || isWebp(header) || isHeicSignature(header);
    }

    /**
     * Detecta HEIC/HEIF mirando el <b>contenido real</b> del archivo, no la extensión
     * del nombre ni el Content-Type declarado: caja ISOBMFF "ftyp" en los bytes 4-7 y
     * un brand HEIF conocido en los bytes 8-11 (heic/heix/mif1/msf1 y variantes).
     *
     * <p>Un archivo cuyo nombre termina en {@code .heic} pero cuyos primeros bytes son
     * JPEG/PNG/WEBP devuelve {@code false} acá: ya es una imagen que el navegador puede
     * mostrar y no debe pasar por heif-convert.
     *
     * @param header al menos los primeros 12 bytes del archivo.
     * @return true si la firma binaria corresponde a HEIC/HEIF.
     */
    public static boolean isHeicSignature(byte[] header) {
        if (header == null || header.length < 12) {
            return false;
        }
        boolean hasFtypBox = header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        if (!hasFtypBox) {
            return false;
        }
        String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
        return switch (brand) {
            case "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> true;
            default -> false;
        };
    }

    // JPEG: FF D8 FF
    private static boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF
                && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }

    // PNG: 89 50 4E 47 (‰PNG)
    private static boolean isPng(byte[] b) {
        return (b[0] & 0xFF) == 0x89
                && b[1] == 0x50
                && b[2] == 0x4E
                && b[3] == 0x47;
    }

    // WEBP: "RIFF" + 4 bytes de tamaño + "WEBP"
    private static boolean isWebp(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
