package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class HeicConverter {

    /**
     * Tiempo máximo que se espera a que 'heif-convert' termine. Un HEIC malformado o
     * anómalo (48MP raro, archivo corrupto) no puede retener el hilo de request -- ni,
     * indirectamente, una conexión de BD -- de forma indefinida.
     */
    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    private final String command;

    public HeicConverter() {
        this("heif-convert");
    }

    /** Para tests: otro binario con la misma firma {@code <comando> <entrada> <salida>} (ej. cp). */
    HeicConverter(String command) {
        this.command = command;
    }

    /**
     * Convierte una imagen en formato HEIC/HEIF a formato JPEG utilizando el binario
     * de sistema 'heif-convert' instalado en el contenedor Docker vía libheif-examples.
     *
     * @param heicBytes Contenido binario del archivo HEIC original
     * @return Contenido binario de la imagen convertida en JPEG
     */
    public byte[] convertToJpeg(byte[] heicBytes) {
        if (heicBytes == null || heicBytes.length == 0) {
            throw new StorageException("El contenido del archivo HEIC no puede estar vacío");
        }
        Path jpeg;
        try {
            jpeg = convertToJpegFile(new ByteArrayInputStream(heicBytes));
        } catch (IOException e) {
            throw new StorageException("No se pudo escribir el HEIC en un archivo temporal", e);
        }
        try {
            return Files.readAllBytes(jpeg);
        } catch (IOException e) {
            throw new StorageException("No se pudo leer el JPEG convertido", e);
        } finally {
            cleanupTempFile(jpeg.toFile());
        }
    }

    /**
     * Igual que {@link #convertToJpeg(byte[])}, pero sin pasar la foto por el heap: el stream se copia directo al
     * archivo temporal que lee heif-convert, y el JPEG queda en otro archivo temporal.
     *
     * @return el JPEG convertido; quien llama lo borra cuando termina de usarlo
     * @throws IOException      si falla la LECTURA de {@code heic} (o crear el temporal): no dice nada del archivo
     * @throws StorageException si falla la conversión
     */
    public Path convertToJpegFile(InputStream heic) throws IOException {
        String uniqueId = UUID.randomUUID().toString();
        File tempHeicFile = File.createTempFile("upload_" + uniqueId, ".heic");
        try {
            long written = Files.copy(heic, tempHeicFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (written == 0) {
                throw new StorageException("El contenido del archivo HEIC no puede estar vacío");
            }
            return runHeifConvert(tempHeicFile, uniqueId);
        } finally {
            cleanupTempFile(tempHeicFile);
        }
    }

    private Path runHeifConvert(File tempHeicFile, String uniqueId) {
        File tempJpegFile = null;
        File tempErrFile = null;
        Process process = null;
        boolean converted = false;

        try {
            // 1. Archivos temporales de salida aislados en el disco del sistema
            tempJpegFile = File.createTempFile("converted_" + uniqueId, ".jpg");
            tempErrFile = File.createTempFile("heif_err_" + uniqueId, ".log");

            // 2. Configurar ProcessBuilder para invocar el binario heif-convert.
            //    Descartamos stdout y redirigimos stderr a un archivo: si no se
            //    consumieran los streams del proceso y este llenara el buffer del
            //    pipe del SO, quedaría bloqueado y waitFor() nunca retornaría.
            ProcessBuilder processBuilder = new ProcessBuilder(
                    command,
                    tempHeicFile.getAbsolutePath(),
                    tempJpegFile.getAbsolutePath()
            );
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(tempErrFile);

            log.info("Iniciando conversión HEIC a JPEG mediante heif-convert: {}", tempHeicFile.getName());
            process = processBuilder.start();

            // 3. Esperar la finalización del proceso con timeout defensivo
            boolean finished = process.waitFor(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("heif-convert excedió el timeout de {}s; proceso terminado forzosamente", CONVERSION_TIMEOUT_SECONDS);
                throw new StorageException(
                        "La conversión HEIC a JPEG excedió el tiempo máximo permitido (" + CONVERSION_TIMEOUT_SECONDS + "s).");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errorMessage = readErrFile(tempErrFile);
                log.error("Fallo en heif-convert con código {}: {}", exitCode, errorMessage);
                throw new StorageException("Error durante la conversión HEIC a JPEG: " + errorMessage);
            }

            log.info("Conversión HEIC exitosa. Tamaño resultante JPEG: {} bytes", tempJpegFile.length());
            converted = true;
            return tempJpegFile.toPath();

        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("No se pudo ejecutar heif-convert localmente (posible entorno de desarrollo sin libheif instalado): {}", e.getMessage());
            throw new StorageException("El binario de conversión heif-convert no está disponible en este entorno.", e);
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new StorageException("La conversión HEIC fue interrumpida inesperadamente", e);
        } finally {
            // 4. Garantizar la eliminación de los temporales (el JPEG solo si no se entrega)
            cleanupTempFile(tempErrFile);
            if (!converted) {
                cleanupTempFile(tempJpegFile);
            }
        }
    }

    private String readErrFile(File errFile) {
        if (errFile == null || !errFile.exists()) {
            return "(sin salida de error)";
        }
        try {
            String content = new String(Files.readAllBytes(errFile.toPath()), StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "(sin salida de error)" : content;
        } catch (IOException e) {
            return "(no se pudo leer la salida de error: " + e.getMessage() + ")";
        }
    }

    private void cleanupTempFile(File file) {
        if (file != null && file.exists()) {
            try {
                boolean deleted = file.delete();
                if (!deleted) {
                    file.deleteOnExit();
                }
            } catch (Exception e) {
                log.warn("No se pudo eliminar el archivo temporal: {}", file.getAbsolutePath());
            }
        }
    }
}
