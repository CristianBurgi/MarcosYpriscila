package com.tuapp.eventfoto.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Falla el arranque de la aplicación si {@code app.storage.mode=r2} pero las
 * credenciales de Cloudflare R2 no están seteadas o parecen valores placeholder.
 *
 * Motivo: {@link com.tuapp.eventfoto.storage.R2StorageService} degrada en SILENCIO a
 * modo local (disco) cuando el endpoint contiene "placeholder"/"dummy" o no puede
 * firmar URLs -- con esa degradación, las fotos "se suben OK" pero nunca llegan a R2
 * y desaparecen. Preferimos no arrancar antes que perder fotos el día del evento.
 */
@Slf4j
@Component
public class R2ConfigurationValidator {

    private static final List<String> PLACEHOLDER_MARKERS = List.of("placeholder", "dummy", "<");

    @Value("${app.storage.mode:local}")
    private String storageMode;

    @Value("${cloudflare.r2.access-key:}")
    private String accessKey;

    @Value("${cloudflare.r2.secret-key:}")
    private String secretKey;

    @Value("${cloudflare.r2.endpoint:}")
    private String endpoint;

    @Value("${cloudflare.r2.bucket-name:}")
    private String bucketName;

    @PostConstruct
    void validateR2ConfigurationOnStartup() {
        String mode = storageMode == null ? "" : storageMode.trim();
        if (!"r2".equalsIgnoreCase(mode)) {
            log.info("app.storage.mode='{}' (no es 'r2'): se omite la validación de credenciales de Cloudflare R2.", storageMode);
            return;
        }

        List<String> problemas = new ArrayList<>();
        checkVar(problemas, "R2_ACCESS_KEY (cloudflare.r2.access-key)", accessKey);
        checkVar(problemas, "R2_SECRET_KEY (cloudflare.r2.secret-key)", secretKey);
        checkVar(problemas, "R2_ENDPOINT (cloudflare.r2.endpoint)", endpoint);
        checkVar(problemas, "R2_BUCKET (cloudflare.r2.bucket-name)", bucketName);

        if (!problemas.isEmpty()) {
            String mensaje = "app.storage.mode=r2 pero la configuración de Cloudflare R2 es inválida:\n  - "
                    + String.join("\n  - ", problemas)
                    + "\n\nSeteá las variables de entorno reales (R2_ACCESS_KEY, R2_SECRET_KEY, R2_ENDPOINT, R2_BUCKET)"
                    + " o cambiá STORAGE_MODE a 'local'. La aplicación NO arranca en modo r2 con credenciales"
                    + " placeholder para evitar degradar en silencio a almacenamiento en disco local y perder fotos.";
            throw new IllegalStateException(mensaje);
        }

        log.info("Configuración de Cloudflare R2 validada correctamente en el arranque (endpoint: '{}', bucket: '{}').",
                endpoint, bucketName);
    }

    private void checkVar(List<String> problemas, String nombre, String valor) {
        if (valor == null || valor.isBlank()) {
            problemas.add(nombre + ": no está seteada");
            return;
        }
        String lower = valor.toLowerCase();
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                problemas.add(nombre + ": parece un valor placeholder ('" + valor + "')");
                return;
            }
        }
    }
}
