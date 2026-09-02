package com.tuapp.eventfoto.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre la decisión clave del hardening de HEIC: si convertir o no se resuelve
 * mirando los magic bytes reales, nunca la extensión del nombre.
 */
class FileSignatureValidatorTest {

    /** Construye un header ISOBMFF: [size(4)] "ftyp" [brand(4)] ... */
    private static byte[] ftypHeader(String brand) {
        byte[] h = new byte[12];
        h[0] = 0x00; h[1] = 0x00; h[2] = 0x00; h[3] = 0x18; // box size
        h[4] = 'f'; h[5] = 't'; h[6] = 'y'; h[7] = 'p';
        byte[] b = brand.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, 8, 4);
        return h;
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void detectaBrandsHeicHeifPorContenido() {
        for (String brand : new String[]{"heic", "heix", "mif1", "msf1", "hevc", "heim"}) {
            assertThat(FileSignatureValidator.isHeicSignature(ftypHeader(brand)))
                    .as("brand %s", brand).isTrue();
        }
    }

    @Test
    void jpegPngWebpNoSonHeic_aunqueElNombreDigaHeic() {
        assertThat(FileSignatureValidator.isHeicSignature(JPEG)).isFalse();
        assertThat(FileSignatureValidator.isHeicSignature(PNG)).isFalse();
        assertThat(FileSignatureValidator.isHeicSignature(WEBP)).isFalse();
    }

    @Test
    void headerNuloOCortoNoEsHeic() {
        assertThat(FileSignatureValidator.isHeicSignature(null)).isFalse();
        assertThat(FileSignatureValidator.isHeicSignature(new byte[]{'f', 't', 'y', 'p'})).isFalse();
    }

    @Test
    void ftypConBrandDesconocidoNoEsHeic() {
        assertThat(FileSignatureValidator.isHeicSignature(ftypHeader("qt  "))).isFalse();
    }

    @Test
    void isValidImageSignatureSigueAceptandoHeicYNoHeic() {
        assertThat(FileSignatureValidator.isValidImageSignature(ftypHeader("heic"))).isTrue();
        assertThat(FileSignatureValidator.isValidImageSignature(JPEG)).isTrue();
        assertThat(FileSignatureValidator.isValidImageSignature(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})).isFalse();
    }
}
