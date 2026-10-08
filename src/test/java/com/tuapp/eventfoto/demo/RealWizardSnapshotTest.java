package com.tuapp.eventfoto.demo;

import com.tuapp.eventfoto.event.dto.EventSettingsDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fase 9.7-B: el wizard de la demo reusa fragmentos de admin/wizard.html (th:fragment). Thymeleaf saca esos
 * atributos al renderizar, así que el wizard real tiene que salir idéntico, byte a byte, a como salía en main antes
 * del cambio. Los snapshots (src/test/resources/golden/) se grabaron con el template de main; para regrabarlos a
 * propósito: -Dgolden.record=true.
 */
class RealWizardSnapshotTest {

    @ParameterizedTest
    @ValueSource(strings = {"blank", "branded"})
    @DisplayName("El wizard real renderiza idéntico al de main (sin rastros de los fragmentos de la demo)")
    void realWizardIsUnchanged(String variant) throws Exception {
        String html = render(variant);
        Path golden = Path.of("src/test/resources/golden/admin-wizard-" + variant + ".html");
        if (Boolean.getBoolean("golden.record")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, html, StandardCharsets.UTF_8);
        }
        // Los goldens pueden quedar con CRLF según core.autocrlf: se compara con LF.
        assertEquals(Files.readString(golden, StandardCharsets.UTF_8).replace("\r\n", "\n"), html.replace("\r\n", "\n"));
    }

    static String render(String variant) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        Context context = new Context();
        context.setVariable("slug", "cumple-de-sofia-k7m2xq9p");
        context.setVariable("eventName", "Cumple de 15 de Sofía");
        context.setVariable("minDate", LocalDate.of(2026, 10, 8));
        context.setVariable("maxDate", LocalDate.of(2028, 10, 8));
        context.setVariable("settings", variant.equals("blank")
                ? new EventSettingsDTO(null, null, null, true, false)
                : new EventSettingsDTO(LocalDate.of(2026, 12, 12), "#7b2d8e", "/uploads/events/x/branding/y.jpg", true, false));
        return engine.process("admin/wizard", context);
    }
}
