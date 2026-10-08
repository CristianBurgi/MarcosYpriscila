package com.tuapp.eventfoto.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fase 9.7-B: con app.demo.enabled=false, las páginas muestran "no disponible" y la API responde 503. */
@SpringBootTest(properties = "app.demo.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDisabledTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Apagada: /demo y /demo/{sid}/... muestran el aviso con 503")
    void pagesShowUnavailable() throws Exception {
        String sid = DemoService.newSid();
        for (String path : new String[]{"/demo", "/demo?fin=1", "/demo/" + sid, "/demo/" + sid + "/pantalla", "/demo/" + sid + "/subir"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(containsString("La demo no está disponible en este momento.")));
        }
    }

    @Test
    @DisplayName("Apagada: la API responde 503 con el mensaje en JSON, también al crear una demo")
    void apiRespondsServiceUnavailable() throws Exception {
        mockMvc.perform(multipart("/api/v1/demo/sessions").param("date", "2026-12-12").param("color", "#1f6f5c"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("La demo no está disponible en este momento."));
        mockMvc.perform(get("/api/v1/demo/" + DemoService.newSid() + "/photos"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/demo/rules").param("date", "2026-12-12"))
                .andExpect(status().isServiceUnavailable());
    }
}
