package com.tuapp.eventfoto.demo;

import com.tuapp.eventfoto.event.EventBrandingHead;
import com.tuapp.eventfoto.event.EventPalette;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Páginas de la demo (Fase 9.7-B). Sirven las MISMAS páginas de invitados que un evento real (classpath:guest-pages)
 * con un {@code <script>} inyectado justo antes de event-context.js que define EVENT_API, EVENT_BASE y
 * EVENT_STORAGE_PREFIX; event-context.js solo completa lo que no viene definido. Además se agrega la personalización
 * de la demo (EventBrandingHead, como un evento real) y demo.css/demo.js. La subida es una página propia
 * (demo-upload.html) y el wizard un template que reusa los fragmentos del real.
 *
 * Un sid con otro formato, inexistente o vencido redirige a /demo?fin=1 ("Tu demo terminó"). El sid y el color se
 * validan antes de inyectarlos en el HTML.
 */
@Controller
@RequiredArgsConstructor
public class DemoPageController {

    static final String CONTEXT_SCRIPT = "<script src=\"/js/event-context.js\"></script>";
    static final String ENDED_REDIRECT = "/demo?fin=1";

    private final DemoService demoService;

    @GetMapping("/demo")
    public String wizard(@RequestParam(name = "fin", required = false) String ended, Model model) {
        model.addAttribute("ended", ended != null);
        model.addAttribute("eventName", "Demo en vivo"); // el título de la vista previa (fragmento del wizard real)
        return "demo/wizard";
    }

    @GetMapping("/demo/{sid}")
    public ResponseEntity<?> menu(@PathVariable String sid) {
        return page(sid, "menu");
    }

    @GetMapping("/demo/{sid}/subir")
    public ResponseEntity<?> upload(@PathVariable String sid) {
        return page(sid, "demo-upload");
    }

    @GetMapping("/demo/{sid}/album")
    public ResponseEntity<?> album(@PathVariable String sid) {
        return page(sid, "album");
    }

    @GetMapping("/demo/{sid}/mensajes")
    public ResponseEntity<?> messages(@PathVariable String sid) {
        return page(sid, "messages");
    }

    @GetMapping("/demo/{sid}/pantalla")
    public ResponseEntity<?> screen(@PathVariable String sid) {
        return page(sid, "screen");
    }

    private ResponseEntity<?> page(String sid, String name) {
        Optional<DemoSession> found = demoService.findActive(sid); // valida el formato antes de ir a la base
        if (found.isEmpty() || !EventPalette.HEX.matcher(found.get().getColor()).matches()) {
            return ResponseEntity.status(302).header("Location", ENDED_REDIRECT).build();
        }
        DemoSession session = found.get();
        String html;
        try {
            html = new ClassPathResource("guest-pages/" + name + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        html = injectContext(html, session.getSid(), name);
        html = EventBrandingHead.injectBeforeHeadEnd(html,
                EventBrandingHead.of(session.getColor(), demoService.backgroundUrl(session))
                        + "<link rel=\"stylesheet\" href=\"/css/demo.css?v=1\">\n"
                        + "<script src=\"/js/demo.js?v=1\" defer></script>\n");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(html);
    }

    /** El script va ANTES de event-context.js; sin ese tag la página no sirve para la demo y se corta acá. */
    static String injectContext(String html, String sid, String page) {
        if (!DemoService.isValidSid(sid)) {
            throw new IllegalArgumentException("sid de demo inválido");
        }
        int at = html.indexOf(CONTEXT_SCRIPT);
        if (at < 0) {
            throw new IllegalStateException("La página " + page + " no carga event-context.js");
        }
        String script = "<script>window.EVENT_API=\"/api/v1/demo/" + sid + "\";window.EVENT_BASE=\"/demo/" + sid
                + "\";window.EVENT_STORAGE_PREFIX=\"demo:" + sid + ":\";window.DEMO_PAGE=\"" + page + "\";</script>\n";
        return html.substring(0, at) + script + html.substring(at);
    }
}
