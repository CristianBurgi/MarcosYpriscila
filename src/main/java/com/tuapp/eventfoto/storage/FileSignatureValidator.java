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
        return isJpeg(header) || isPng(header) || isWebp(header) || isHeicOrHeif(header);
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

    // HEIC/HEIF: caja ISOBMFF "ftyp" en bytes 4-7, con brand HEIC en bytes 8-11
    private static boolean isHeicOrHeif(byte[] b) {
        boolean hasFtypBox = b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
        if (!hasFtypBox) {
            return false;
        }
        String brand = new String(b, 8, 4, StandardCharsets.US_ASCII);
        return switch (brand) {
            case "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> true;
            default -> false;
        };
    }
}
