package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

@Slf4j
@Component
public class HeicConverter {

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

        try {
            // 1. Crear archivos temporales aislados en el disco del sistema
            String uniqueId = UUID.randomUUID().toString();
            tempHeicFile = File.createTempFile("upload_" + uniqueId, ".heic");
            tempJpegFile = File.createTempFile("converted_" + uniqueId, ".jpg");

            // 2. Escribir los bytes de entrada HEIC en el archivo temporal
            try (FileOutputStream fos = new FileOutputStream(tempHeicFile)) {
                fos.write(heicBytes);
            }

            // 3. Configurar ProcessBuilder para invocar el binario heif-convert
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "heif-convert",
                    tempHeicFile.getAbsolutePath(),
                    tempJpegFile.getAbsolutePath()
            );

            log.info("Iniciando conversión HEIC a JPEG mediante heif-convert: {}", tempHeicFile.getName());
            Process process = processBuilder.start();

            // 4. Esperar la finalización del proceso
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorMessage;
                try (InputStream es = process.getErrorStream()) {
                    errorMessage = new String(es.readAllBytes());
                }
                log.error("Fallo en heif-convert con código {}: {}", exitCode, errorMessage);
                throw new StorageException("Error durante la conversión HEIC a JPEG: " + errorMessage);
            }

            // 5. Leer los bytes de la foto convertida a JPEG
            byte[] jpegBytes = Files.readAllBytes(tempJpegFile.toPath());
            log.info("Conversión HEIC exitosa. Tamaño resultante JPEG: {} bytes", jpegBytes.length);
            return jpegBytes;

        } catch (IOException e) {
            log.warn("No se pudo ejecutar heif-convert localmente (posible entorno de desarrollo sin libheif instalado): {}", e.getMessage());
            // En desarrollo local sin el binario instalado, se retorna el arreglo original o excepción de fallback
            throw new StorageException("El binario de conversión heif-convert no está disponible en este entorno.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("La conversión HEIC fue interrumpida inesperadamente", e);
        } finally {
            // 6. Garantizar la eliminación de los archivos temporales de disco
            cleanupTempFile(tempHeicFile);
            cleanupTempFile(tempJpegFile);
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
