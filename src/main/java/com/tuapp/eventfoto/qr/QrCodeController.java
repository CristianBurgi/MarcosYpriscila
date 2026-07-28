package com.tuapp.eventfoto.qr;

import com.tuapp.eventfoto.event.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class QrCodeController {

    private final QrCodeService qrCodeService;
    private final EventService eventService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * GET /api/v1/events/{slug}/qr
     * Devuelve la imagen PNG del código QR que apunta al menú del evento en producción.
     */
    @GetMapping("/{slug}/qr")
    public ResponseEntity<byte[]> getEventQrCode(
            @PathVariable String slug,
            @RequestParam(defaultValue = "400") int size) {

        // Validar existencia del evento
        eventService.getEventBySlug(slug);

        int dimension = Math.min(Math.max(size, 100), 1000);

        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String targetUrl = String.format("%s/menu.html?slug=%s", cleanBaseUrl, slug);

        byte[] qrBytes = qrCodeService.generateQrCodePng(targetUrl, dimension, dimension);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"qr-" + slug + ".png\"")
                .body(qrBytes);
    }
}
