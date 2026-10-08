package com.tuapp.eventfoto.kit;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.OwnedEvent;
import com.tuapp.eventfoto.kit.VisibilityKitService.Format;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Descarga del kit de visibilidad (Fase 9.8). Bajo /api/v1/admin/**: rol ORGANIZER y EventAccessInterceptor. */
@RestController
@RequestMapping("/api/v1/admin/events/{slug}")
@RequiredArgsConstructor
public class AdminVisibilityKitController {

    private final VisibilityKitService visibilityKitService;

    /** GET .../tarjetas.pdf: A4 con 4 tarjetas A6 para las mesas. */
    @GetMapping("/tarjetas.pdf")
    public ResponseEntity<byte[]> cards(@OwnedEvent Event event) {
        return pdf(event, Format.CARDS);
    }

    /** GET .../cartel.pdf: un A4 para la entrada, la barra o la mesa dulce. */
    @GetMapping("/cartel.pdf")
    public ResponseEntity<byte[]> poster(@OwnedEvent Event event) {
        return pdf(event, Format.POSTER);
    }

    private ResponseEntity<byte[]> pdf(Event event, Format format) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(format.filename).build().toString())
                .body(visibilityKitService.render(event, format));
    }
}
