package com.tuapp.eventfoto.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.tuapp.eventfoto.common.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class QrCodeService {

    /**
     * Genera un código QR en formato de imagen PNG conteniendo la URL especificada.
     *
     * @param targetUrl URL a la que apuntará el código QR
     * @param width Ancho de la imagen en píxeles
     * @param height Alto de la imagen en píxeles
     * @return Arreglo de bytes representando la imagen PNG del código QR
     */
    public byte[] generateQrCodePng(String targetUrl, int width, int height) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("La URL destino para el código QR no puede estar vacía.");
        }

        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1); // Margen reducido para código QR más limpio

            BitMatrix bitMatrix = qrCodeWriter.encode(targetUrl, BarcodeFormat.QR_CODE, width, height, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            log.info("Código QR generado exitosamente para URL: '{}' ({}x{}px)", targetUrl, width, height);
            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("Error al generar código QR para URL '{}': {}", targetUrl, e.getMessage(), e);
            throw new StorageException("Error al generar la imagen del código QR", e);
        }
    }
}
