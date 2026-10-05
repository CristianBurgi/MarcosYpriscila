package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.LogCapture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fase 9.3: las páginas /comprar y /compra/retorno y el botón de "Mis eventos" (interruptor prendido). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutPagesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void cleanUp() {
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("/compra/retorno: estado neutro visible por defecto (sin referencia no queda 'confirmando' trabado) y recuerda la referencia en sessionStorage")
    void returnPageSurvivesAReload() throws Exception {
        String body = mockMvc.perform(get("/compra/retorno")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("class=\"state visible\" id=\"state-none\"")
                .contains("Si ya pagaste, en unos minutos vas a poder iniciar sesión")
                .contains("class=\"state\" id=\"state-pending\"")
                .contains("sessionStorage.setItem").contains("sessionStorage.getItem");
    }

    @Test
    @DisplayName("/comprar es pública y trae el formulario")
    void buyPageIsPublic() throws Exception {
        mockMvc.perform(get("/comprar")).andExpect(status().isOk())
                .andExpect(content().string(containsString("buyForm")))
                .andExpect(content().string(containsString("passwordConfirmation")));
    }

    @Test
    @DisplayName("/compra/retorno: no-store y no-referrer, texto fijo para cualquier resultado, nada de la URL reflejado ni logueado")
    void returnPageIsLockedDown() throws Exception {
        String reference = "0b8f6a52-1c3e-4d0a-9d52-6a2f6e0c7b11";
        try (LogCapture logs = LogCapture.start()) {
            var result = mockMvc.perform(get("/compra/retorno")
                            .param("external_reference", reference).param("status", "valor-reflejado-status")
                            .param("collection_status", "valor-reflejado-collection").param("payment_id", "1234567890"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(content().string(containsString("Estamos confirmando tu pago")))
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain(reference).doesNotContain("valor-reflejado").doesNotContain("1234567890");
            assertThat(result.getResponse().getHeader("Set-Cookie")).as("sin login automático: ninguna cookie").isNull();
            assertThat(logs.text()).doesNotContain(reference).doesNotContain("1234567890");
        }
        mockMvc.perform(get("/compra/retorno").param("status", "valor-reflejado-2").param("collection_status", "valor-reflejado-3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Estamos confirmando tu pago")))
                .andExpect(content().string(not(containsString("valor-reflejado"))));
    }

    @Test
    @DisplayName("Con el checkout prendido, \"Mis eventos\" muestra el botón \"Crear nuevo evento\"")
    void myEventsShowsTheButtonWhenEnabled() throws Exception {
        Organizer organizer = organizerRepository.save(Organizer.builder().email("boton@test.com").build());
        Cookie cookie = new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
        mockMvc.perform(get("/admin/eventos").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Crear nuevo evento")));
    }
}
