package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con app.checkout.enabled=false (y SIN credenciales de MP): arranca, y no existe ni una ruta ni el botón. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.checkout.enabled=false", "app.checkout.mp-access-token=", "app.checkout.mp-webhook-secret="})
class CheckoutDisabledTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PendingPurchaseCleanupJob cleanupJob;
    @Autowired private PendingPurchaseRepository purchases;

    @AfterEach
    void cleanUp() {
        purchases.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("Checkout apagado: el job de limpieza corre igual; una compra de más de 72 hs se descarta y una reciente no")
    void cleanupRunsWithCheckoutOff() {
        PendingPurchase old = purchases.save(PendingPurchase.builder().email("vieja@test.com").passwordHash("$2a$10$hash")
                .eventName("Evento").amount(new java.math.BigDecimal("50000.00")).mpPreferenceId("pref-1")
                .createdAt(java.time.Instant.now().minus(java.time.Duration.ofHours(73))).build());
        PendingPurchase recent = purchases.save(PendingPurchase.builder().email("reciente@test.com").passwordHash("$2a$10$hash")
                .eventName("Evento").amount(new java.math.BigDecimal("50000.00")).mpPreferenceId("pref-2")
                .createdAt(java.time.Instant.now().minus(java.time.Duration.ofHours(2))).build());

        cleanupJob.run();

        assertThat(purchases.findById(old.getId())).isEmpty();
        assertThat(purchases.findById(recent.getId())).isPresent();
    }

    @Test
    @DisplayName("La app arranca sin credenciales y no registra ningún bean del checkout (salvo la limpieza, que no depende de MP)")
    void startsWithoutCredentialsAndWithoutCheckoutBeans() {
        assertThat(context.getBeansOfType(CheckoutService.class)).isEmpty();
        assertThat(context.getBeansOfType(CheckoutController.class)).isEmpty();
        assertThat(context.getBeansOfType(CheckoutViewController.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentPreferenceGateway.class)).isEmpty();
        assertThat(context.getBeansOfType(PurchaseConfirmationService.class)).isEmpty();
        assertThat(context.getBeansOfType(PurchaseConfirmationController.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentLookupGateway.class)).isEmpty();
    }

    @Test
    @DisplayName("Rutas públicas del checkout -> 404 (nada de 405 ni 500)")
    void publicRoutesAre404() throws Exception {
        mockMvc.perform(get("/comprar")).andExpect(status().isNotFound());
        mockMvc.perform(get("/compra/retorno")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/checkout").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        // Fase 9.4
        mockMvc.perform(post("/api/v1/checkout/webhook").queryParam("data.id", "1").queryParam("type", "payment")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/checkout/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"1\"}")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/checkout/status").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Con sesión de organizador, POST /api/v1/admin/checkout -> 404 y \"Mis eventos\" no muestra el botón")
    void adminRouteIs404AndTheButtonIsHidden() throws Exception {
        Organizer organizer = organizerRepository.save(Organizer.builder().email("apagado@test.com").build());
        Cookie cookie = new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));

        mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{\"eventName\":\"X\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/eventos").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Crear nuevo evento"))))
                .andExpect(content().string(not(containsString("/api/v1/admin/checkout"))));
    }
}
