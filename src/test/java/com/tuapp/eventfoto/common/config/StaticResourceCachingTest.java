package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase L7: los estáticos públicos se cachean un día (spring.web.resources.cache) y Spring Security no los pisa con
 * no-store; la landing va con no-cache para entrar al back/forward cache. Las rutas con credenciales siguen con no-store
 * y no-referrer (más casos en CheckoutPagesTest, ModeratorTokenLeakTest y PasswordResetFlowTest).
 * Misma configuración de contexto que el resto para no sumar uno a la caché.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticResourceCachingTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("CSS, JS, imágenes de la landing y de la demo y favicon: Cache-Control público con max-age de un día")
    void staticResourcesAreCached() throws Exception {
        for (String path : new String[] {"/css/theme.css", "/js/event-context.js", "/images/landing/pantalla-1-640.webp",
                "/img/demo/demo-01.webp", "/favicon.ico"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                    .andExpect(header().doesNotExist("Pragma"))
                    .andExpect(header().doesNotExist("Expires"));
        }
    }

    @Test
    @DisplayName("La landing revalida siempre (no-cache) pero sin no-store, que la deja afuera del back/forward cache")
    void landingIsNoCacheButNotNoStore() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    @DisplayName("Las rutas con credenciales siguen con no-store y no-referrer")
    void credentialRoutesStayNoStore() throws Exception {
        for (String path : new String[] {"/activar-cuenta?token=abc", "/restablecer-contrasena?token=abc",
                "/compra/retorno?external_reference=abc", "/moderar/token-inexistente"}) {
            mockMvc.perform(get(path))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @Test
    @DisplayName("Una página dinámica que no es la landing conserva el no-store de Spring Security")
    void otherPagesKeepNoStore() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
