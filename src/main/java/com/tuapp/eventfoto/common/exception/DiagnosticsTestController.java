package com.tuapp.eventfoto.common.exception;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller de diagnóstico exclusivo para verificar la integración con Sentry
 * en ambientes de desarrollo/prueba, sin necesidad de forzar un error en
 * producción real.
 *
 * Restringido con @Profile al mismo patrón que StorageTestController: en
 * Railway no se activa ningún profile de Spring (perfil "default"), así que
 * este controller nunca se registra en producción.
 */
@RestController
@RequestMapping("/api/diagnostics/test")
@Profile({"dev", "local", "test"})
public class DiagnosticsTestController {

    @GetMapping("/throw")
    public void throwTestException() {
        throw new RuntimeException("Excepción de prueba forzada para verificar integración con Sentry");
    }
}
