package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Páginas públicas del evento. El slug va en el path y este es el contrato de URL que
 * termina impreso en los QR de las tarjetas de mesa -- no cambiarlo:
 *
 *   /e/{slug}            menú          /e/{slug}/album      álbum
 *   /e/{slug}/subir      subir foto    /e/{slug}/mensajes   libro de visitas
 *   /e/{slug}/pantalla   pantalla del salón
 *
 * Los HTML viven en classpath:guest-pages (no en static/) para que solo se sirvan por acá,
 * con el evento ya validado. Un slug inexistente devuelve la página "evento no encontrado"
 * con 404 (ver EventPageExceptionHandler).
 */
@Controller
@RequiredArgsConstructor
public class GuestPageController {

    /** Formato del SlugGenerator (minúsculas, dígitos y guiones); descarta todo lo demás antes de redirigir. */
    private static final Pattern SLUG_FORMAT = Pattern.compile("[a-z0-9-]{1,100}");

    /** Color de la paleta cuando hay imagen de fondo pero no color: el vino de la app. */
    static final String DEFAULT_COLOR = "#3a0f14";

    private final EventService eventService;
    private final StorageService storageService;

    @GetMapping("/e/{slug}")
    public ResponseEntity<Resource> menu(@PathVariable String slug) {
        return page(slug, "menu");
    }

    @GetMapping("/e/{slug}/subir")
    public ResponseEntity<Resource> upload(@PathVariable String slug) {
        return page(slug, "upload");
    }

    @GetMapping("/e/{slug}/album")
    public ResponseEntity<Resource> album(@PathVariable String slug) {
        return page(slug, "album");
    }

    @GetMapping("/e/{slug}/mensajes")
    public ResponseEntity<Resource> messages(@PathVariable String slug) {
        return page(slug, "messages");
    }

    @GetMapping("/e/{slug}/pantalla")
    public ResponseEntity<Resource> screen(@PathVariable String slug) {
        return page(slug, "screen");
    }

    // --- URLs viejas (/menu.html?slug=X): 302 (no 301) hacia el formato nuevo ---

    @GetMapping("/menu.html")
    public ResponseEntity<Void> legacyMenu(@RequestParam(required = false) String slug) {
        return legacyRedirect(slug, "");
    }

    @GetMapping("/upload.html")
    public ResponseEntity<Void> legacyUpload(@RequestParam(required = false) String slug) {
        return legacyRedirect(slug, "/subir");
    }

    @GetMapping("/album.html")
    public ResponseEntity<Void> legacyAlbum(@RequestParam(required = false) String slug) {
        return legacyRedirect(slug, "/album");
    }

    @GetMapping("/messages.html")
    public ResponseEntity<Void> legacyMessages(@RequestParam(required = false) String slug) {
        return legacyRedirect(slug, "/mensajes");
    }

    @GetMapping("/screen.html")
    public ResponseEntity<Void> legacyScreen(@RequestParam(required = false) String slug) {
        return legacyRedirect(slug, "/pantalla");
    }

    private ResponseEntity<Void> legacyRedirect(String slug, String suffix) {
        // Sin slug (o con uno que no puede existir) no hay evento al que llevar al invitado.
        // El formato validado (solo a-z, 0-9 y guion) evita armar un Location con contenido ajeno.
        String location = slug != null && SLUG_FORMAT.matcher(slug).matches() ? "/e/" + slug + suffix : "/";
        return ResponseEntity.status(302).header("Location", location).build();
    }

    private ResponseEntity<Resource> page(String slug, String name) {
        Event event = eventService.getEventEntityBySlug(slug); // 404 si no existe
        Resource html = new ClassPathResource("guest-pages/" + name + ".html");
        if (!name.equals("screen") && (event.getBackgroundColor() != null || event.getBackgroundImageKey() != null)) {
            html = withBranding(html, event);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .body(html);
    }

    /**
     * Personalización del evento (Fase 9.5): agrega antes de {@code </head>} la hoja /css/event-branding.css y
     * las variables de la paleta. Va del lado del servidor para que la página no aparezca un instante con los
     * colores por defecto. Sin personalización no se llama: la página sale byte a byte como está en el classpath.
     * La pantalla del salón no se personaliza todavía (9.8).
     */
    private Resource withBranding(Resource page, Event event) {
        String imageKey = event.getBackgroundImageKey();
        // La URL sale solo de una clave generada por nosotros con el formato exacto (UUID), nunca de un input;
        // igual se valida (y EventBrandingHead la escapa para el string CSS).
        String imageUrl = imageKey != null && StorageKeys.isBrandingKeyOf(event.getId(), imageKey)
                ? storageService.generatePublicUrl(imageKey) : null;
        String color = event.getBackgroundColor() != null ? event.getBackgroundColor() : DEFAULT_COLOR;
        try {
            String html = page.getContentAsString(StandardCharsets.UTF_8);
            return new ByteArrayResource(EventBrandingHead.injectBeforeHeadEnd(html, EventBrandingHead.of(color, imageUrl))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
