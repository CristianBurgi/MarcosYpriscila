package com.tuapp.eventfoto.superadmin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con el checkout apagado el panel arranca igual; las dos acciones de pago responden 409 con un mensaje claro. */
@SpringBootTest(properties = "app.checkout.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuperadminCheckoutDisabledTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Checkout apagado: listado 200; marcar pago aprobado y reenviar mail -> 409 'El checkout está deshabilitado.'")
    void paymentActionsAreConflicts() throws Exception {
        mockMvc.perform(get("/superadmin/eventos").with(user("superadmin@boda.com").roles("SUPERADMIN"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/superadmin/payments/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"1\"}")
                        .with(user("superadmin@boda.com").roles("SUPERADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("El checkout está deshabilitado."));
        mockMvc.perform(post("/api/v1/superadmin/events/" + UUID.randomUUID() + "/resend-confirmation").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(user("superadmin@boda.com").roles("SUPERADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("El checkout está deshabilitado."));
    }
}
