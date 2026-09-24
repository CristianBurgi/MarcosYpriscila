package com.tuapp.eventfoto.message;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Descarga del libro de visitas en PDF. Solo admin: la ruta cae bajo /api/v1/admin/**,
 * que SecurityConfig restringe al rol ADMIN.
 */
@RestController
@RequiredArgsConstructor
public class AdminGuestbookController {

    private final GuestbookPdfService guestbookPdfService;

    /** GET /api/v1/admin/events/{slug}/libro-de-visitas.pdf */
    @GetMapping("/api/v1/admin/events/{slug}/libro-de-visitas.pdf")
    public ResponseEntity<byte[]> downloadGuestbook(@PathVariable String slug) {
        byte[] pdf = guestbookPdfService.generate(slug);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(GuestbookPdfService.FILENAME).build().toString())
                .body(pdf);
    }
}
