package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        File tempHeicFile = null;
        File tempJpegFile = null;
        File tempErrFile = null;
        Process process = null;

        try {
            // 1. Crear archivos temporales aislados en el disco del sistema
            String uniqueId = UUID.randomUUID().toString();
            tempHeicFile = File.createTempFile("upload_" + uniqueId, ".heic");
            tempJpegFile = File.createTempFile("converted_" + uniqueId, ".jpg");
            tempErrFile = File.createTempFile("heif_err_" + uniqueId, ".log");

            // 2. Escribir los bytes de entrada HEIC en el archivo temporal
            try (FileOutputStream fos = new FileOutputStream(tempHeicFile)) {
                fos.write(heicBytes);
            }

            // 3. Configurar ProcessBuilder para invocar el binario heif-convert.
            //    Descartamos stdout y redirigimos stderr a un archivo: si no se
            //    consumieran los streams del proceso y este llenara el buffer del
            //    pipe del SO, quedaría bloqueado y waitFor() nunca retornaría.
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "heif-convert",
                    tempHeicFile.getAbsolutePath(),
                    tempJpegFile.getAbsolutePath()
            );
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(tempErrFile);

            log.info("Iniciando conversión HEIC a JPEG mediante heif-convert: {}", tempHeicFile.getName());
            process = processBuilder.start();

            // 4. Esperar la finalización del proceso con timeout defensivo
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

            // 5. Leer los bytes de la foto convertida a JPEG
            byte[] jpegBytes = Files.readAllBytes(tempJpegFile.toPath());
            log.info("Conversión HEIC exitosa. Tamaño resultante JPEG: {} bytes", jpegBytes.length);
            return jpegBytes;

        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("No se pudo ejecutar heif-convert localmente (posible entorno de desarrollo sin libheif instalado): {}", e.getMessage());
            // En desarrollo local sin el binario instalado, se retorna el arreglo original o excepción de fallback
            throw new StorageException("El binario de conversión heif-convert no está disponible en este entorno.", e);
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new StorageException("La conversión HEIC fue interrumpida inesperadamente", e);
        } finally {
            // 6. Garantizar la eliminación de los archivos temporales de disco
            cleanupTempFile(tempHeicFile);
            cleanupTempFile(tempJpegFile);
            cleanupTempFile(tempErrFile);
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
