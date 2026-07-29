package com.tuapp.eventfoto.qr;

import com.tuapp.eventfoto.event.EventService;
import jakarta.servlet.http.HttpServletRequest;
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
     * Devuelve la imagen PNG del código QR que apunta dinámicamente al dominio activo del servidor.
     */
    @GetMapping("/{slug}/qr")
    public ResponseEntity<byte[]> getEventQrCode(
            @PathVariable String slug,
            @RequestParam(defaultValue = "400") int size,
            @RequestParam(defaultValue = "false") boolean download,
            HttpServletRequest request) {

        // Validar existencia del evento
        eventService.getEventBySlug(slug);

        int dimension = Math.min(Math.max(size, 100), 1000);

        String effectiveBaseUrl = resolveEffectiveBaseUrl(request);
        String targetUrl = String.format("%s/menu.html?slug=%s", effectiveBaseUrl, slug);

        log.info("Generando código QR para la URL de destino: '{}'", targetUrl);

        byte[] qrBytes = qrCodeService.generateQrCodePng(targetUrl, dimension, dimension);

        String dispositionType = download ? "attachment" : "inline";

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, dispositionType + "; filename=\"qr-" + slug + ".png\"")
                .body(qrBytes);
    }

    private String resolveEffectiveBaseUrl(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");

        if (forwardedHost != null && !forwardedHost.isBlank()) {
            String host = forwardedHost.split(",")[0].trim();
            String proto = (forwardedProto != null && !forwardedProto.isBlank()) ? forwardedProto.split(",")[0].trim() : "https";
            return proto + "://" + host;
        }

        String serverName = request.getServerName();
        if (serverName != null && !serverName.equals("localhost") && !serverName.equals("127.0.0.1")) {
            String scheme = request.getScheme();
            String proto = ("https".equalsIgnoreCase(scheme) || request.isSecure()) ? "https" : "http";
            int port = request.getServerPort();
            String portSuffix = (port == 80 || port == 443) ? "" : ":" + port;
            return proto + "://" + serverName + portSuffix;
        }

        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.contains("tu-boda") && !baseUrl.contains("localhost")) {
            String clean = baseUrl.trim();
            return clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
        }

        return "https://marcosypriscila-production.up.railway.app";
    }
}
