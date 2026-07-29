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

    @Value("${app.base-url:https://marcosypriscila-production.up.railway.app}")
    private String baseUrl;

    /**
     * GET /api/v1/events/{slug}/qr
     * Devuelve la imagen PNG del código QR que apunta al menú del evento en producción.
     */
    @GetMapping("/{slug}/qr")
    public ResponseEntity<byte[]> getEventQrCode(
            @PathVariable String slug,
            @RequestParam(defaultValue = "400") int size,
            @RequestParam(defaultValue = "false") boolean download) {

        // Validar existencia del evento
        eventService.getEventBySlug(slug);

        int dimension = Math.min(Math.max(size, 100), 1000);

        String cleanBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "https://marcosypriscila-production.up.railway.app";
        if (cleanBaseUrl.endsWith("/")) {
            cleanBaseUrl = cleanBaseUrl.substring(0, cleanBaseUrl.length() - 1);
        }
        String targetUrl = String.format("%s/menu.html?slug=%s", cleanBaseUrl, slug);

        byte[] qrBytes = qrCodeService.generateQrCodePng(targetUrl, dimension, dimension);

        String dispositionType = download ? "attachment" : "inline";

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, dispositionType + "; filename=\"qr-" + slug + ".png\"")
                .body(qrBytes);
    }
}
