package com.tuapp.eventfoto.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * El archivo temporal que recibe heif-convert tiene que ser byte a byte el original que llegó por stream. Con
 * {@code cp} en lugar de heif-convert, la "salida" es una copia exacta de ese temporal: si la copia por stream
 * perdiera o duplicara bytes, el JPEG devuelto no coincidiría con la entrada.
 */
class HeicConverterStreamTest {

    @Test
    @DisplayName("convertToJpegFile: el temporal que lee el conversor es idéntico al stream de entrada")
    void tempFileGivenToConverterIsIdenticalToInput() throws Exception {
        assumeTrue(cpAvailable(), "cp no está en el PATH");
        byte[] original = new byte[300_000];
        new Random(42).nextBytes(original);

        Path out = new HeicConverter("cp").convertToJpegFile(new ByteArrayInputStream(original));
        try {
            assertThat(Arrays.mismatch(Files.readAllBytes(out), original)).as("primer byte distinto (-1 = idénticos)").isEqualTo(-1);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static boolean cpAvailable() {
        try {
            return new ProcessBuilder("cp", "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
