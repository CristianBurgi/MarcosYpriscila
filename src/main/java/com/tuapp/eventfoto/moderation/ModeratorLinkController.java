package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.OwnedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Panel del organizador: genera un link de moderador nuevo (el anterior deja de funcionar al instante). Cuelga de
 * /api/v1/admin/events/{slug}/..., así que lo protege el interceptor (@OwnedEvent) sin excepciones.
 * La respuesta lleva la credencial: no se cachea y no manda Referer (también lo fija SecurityConfig para este prefijo).
 */
@RestController
@RequestMapping("/api/v1/admin/events/{slug}/moderator-link")
@RequiredArgsConstructor
public class ModeratorLinkController {

    private final ModeratorTokenService tokenService;
    private final AppUrls appUrls;

    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(@OwnedEvent Event event) {
        String newToken = tokenService.regenerate(event.getId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(Map.of("link", appUrls.moderatorUrl(newToken)));
    }
}
