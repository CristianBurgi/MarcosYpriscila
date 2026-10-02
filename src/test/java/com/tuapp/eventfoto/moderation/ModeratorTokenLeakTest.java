package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.event.ModeratorTokens;
import com.tuapp.eventfoto.testsupport.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * El token NUNCA sale en una respuesta de error ni en un log, y el lado del moderador (páginas, API y errores)
 * lleva Referrer-Policy: no-referrer y Cache-Control: no-store. Se provocan 200, 400, 404, 405, 429 y un 500 forzado.
 */
class ModeratorTokenLeakTest extends ModeratorTestBase {

    @SpyBean private ModeratorContentService contentService;

    /** Respuesta ya recogida junto con el texto de la request que la provocó. */
    private record Seen(String label, String uri, MockHttpServletResponse response) {
    }

    private Seen call(List<Seen> seen, String label, MockHttpServletRequestBuilder request, String uri) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        Seen result = new Seen(label, uri, response);
        seen.add(result);
        return result;
    }

    @Test
    @DisplayName("200, 400, 404, 405, 429 y un 500 forzado bajo /moderar y /api/v1/moderate: ni el cuerpo ni el log contienen el token, y todas llevan no-referrer + no-store")
    void noTokenInAnyResponseOrLogAndHeadersEverywhere() throws Exception {
        String token = tokenOf(eventA);
        String unknownToken = ModeratorTokens.generate();
        String malformedToken = "no-es-un-token-v4lido";
        List<Seen> seen = new ArrayList<>();

        try (LogCapture logs = LogCapture.start()) {
            // 200: página y API
            assertThat(call(seen, "200 página", get("/moderar/" + token), "/moderar/" + token).response().getStatus()).isEqualTo(200);
            assertThat(call(seen, "200 lista", get("/api/v1/moderate/" + token + "/photos"), "/api/v1/moderate/" + token + "/photos").response().getStatus()).isEqualTo(200);

            // 400: parámetro con formato inválido (UUID mal escrito, page no numérica)
            assertThat(call(seen, "400 uuid", delete("/api/v1/moderate/" + token + "/photos/no-es-un-uuid"), "/api/v1/moderate/" + token + "/photos/no-es-un-uuid").response().getStatus()).isEqualTo(400);
            assertThat(call(seen, "400 page", get("/api/v1/moderate/" + token + "/photos").param("page", "abc"), "/api/v1/moderate/" + token + "/photos").response().getStatus()).isEqualTo(400);

            // 404: token desconocido, mal formado, subruta inexistente, id ajeno
            assertThat(call(seen, "404 desconocido", get("/api/v1/moderate/" + unknownToken + "/photos"), "/api/v1/moderate/" + unknownToken + "/photos").response().getStatus()).isEqualTo(404);
            assertThat(call(seen, "404 mal formado", get("/api/v1/moderate/" + malformedToken + "/photos"), "/api/v1/moderate/" + malformedToken + "/photos").response().getStatus()).isEqualTo(404);
            assertThat(call(seen, "404 página", get("/moderar/" + unknownToken), "/moderar/" + unknownToken).response().getStatus()).isEqualTo(404);
            assertThat(call(seen, "404 subruta API", get("/api/v1/moderate/" + token + "/no-existe"), "/api/v1/moderate/" + token + "/no-existe").response().getStatus()).isEqualTo(404);
            assertThat(call(seen, "404 subruta página", get("/moderar/" + token + "/no-existe"), "/moderar/" + token + "/no-existe").response().getStatus()).isEqualTo(404);
            assertThat(call(seen, "404 id ajeno", delete("/api/v1/moderate/" + token + "/photos/" + photoB.getId()), "/api/v1/moderate/" + token + "/photos/" + photoB.getId()).response().getStatus()).isEqualTo(404);

            // 405: método no soportado
            assertThat(call(seen, "405", post("/api/v1/moderate/" + token + "/photos"), "/api/v1/moderate/" + token + "/photos").response().getStatus()).isEqualTo(405);
            assertThat(call(seen, "405 página", post("/moderar/" + token), "/moderar/" + token).response().getStatus()).isEqualTo(405);

            // 500 forzado: la excepción trae el path con el token en su propio mensaje
            doThrow(new IllegalStateException("falló el acceso en /api/v1/moderate/" + token + "/photos para " + token))
                    .when(contentService).listPhotos(any(), anyInt(), anyInt());
            assertThat(call(seen, "500", get("/api/v1/moderate/" + token + "/photos"), "/api/v1/moderate/" + token + "/photos").response().getStatus()).isEqualTo(500);
            org.mockito.Mockito.reset(contentService);

            // 429: 20 fallos de la misma IP agotan el cupo; después responde 429 aun con un token válido
            rateLimiter.reset();
            for (int i = 0; i < ModeratorRateLimiter.MAX_INVALID_ATTEMPTS; i++) {
                String guess = ModeratorTokens.generate();
                call(seen, "intento inválido " + i, get("/api/v1/moderate/" + guess + "/photos"), "/api/v1/moderate/" + guess + "/photos");
            }
            assertThat(call(seen, "429 inválido", get("/api/v1/moderate/" + unknownToken + "/photos"), "/api/v1/moderate/" + unknownToken + "/photos").response().getStatus()).isEqualTo(429);
            assertThat(call(seen, "429 token válido", get("/api/v1/moderate/" + token + "/photos"), "/api/v1/moderate/" + token + "/photos").response().getStatus()).isEqualTo(429);
            assertThat(call(seen, "429 página", get("/moderar/" + token), "/moderar/" + token).response().getStatus()).isEqualTo(429);
            rateLimiter.reset();

            // 429 por tope de borrados: 60 por minuto por token
            for (int i = 0; i < ModeratorRateLimiter.MAX_DELETES; i++) {
                String missing = UUID.randomUUID().toString();
                call(seen, "borrado " + i, delete("/api/v1/moderate/" + token + "/messages/" + missing), "/api/v1/moderate/" + token + "/messages/" + missing);
            }
            Seen tooMany = call(seen, "429 borrados", delete("/api/v1/moderate/" + token + "/messages/" + messageA.getId()), "/api/v1/moderate/" + token + "/messages/" + messageA.getId());
            assertThat(tooMany.response().getStatus()).isEqualTo(429);
            assertThat(messageRepository.existsById(messageA.getId())).as("el borrado limitado no se ejecutó").isTrue();

            // --- nada del token en los cuerpos ---
            for (Seen s : seen) {
                String body = s.response().getContentAsString();
                for (String secret : new String[]{token, unknownToken, malformedToken}) {
                    assertThat(body).as("cuerpo de %s", s.label()).doesNotContain(secret);
                }
            }
            // --- ni en el log (mensaje, excepción y stack trace) ---
            String log = logs.text();
            for (String secret : new String[]{token, unknownToken, malformedToken}) {
                assertThat(log).as("log completo").doesNotContain(secret);
            }
            for (Seen s : seen) {
                // También los tokens adivinados de los 20 intentos inválidos (el uri completo no debe estar en el log).
                String guess = s.uri().split("/")[s.uri().startsWith("/moderar") ? 2 : 4];
                assertThat(log).as("log (token de '%s')", s.label()).doesNotContain(guess);
            }
            assertThat(log).contains("falló el acceso").contains("{token}"); // el 500 SÍ se registró, con el token enmascarado
        }

        // --- cabeceras en TODAS las respuestas, éxito o error ---
        for (Seen s : seen) {
            assertThat(s.response().getHeader("Referrer-Policy")).as("Referrer-Policy en %s", s.label()).isEqualTo("no-referrer");
            assertThat(s.response().getHeader("Cache-Control")).as("Cache-Control en %s", s.label()).contains("no-store");
        }
    }
}
