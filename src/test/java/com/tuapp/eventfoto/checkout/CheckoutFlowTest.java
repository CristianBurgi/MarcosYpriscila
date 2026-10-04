package com.tuapp.eventfoto.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.testsupport.LogCapture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.3: compra nueva y recompra. El cliente de MP se mockea (el CI no llama a MP). A propósito SIN @Transactional:
 * el servicio guarda, llama a MP y actualiza en pasos separados, y una transacción de test taparía justamente eso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutFlowTest {

    private static final String STRONG_PASSWORD = "una-clave-larga-y-rara-93";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PendingPurchaseRepository purchases;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private PaymentPreferenceGateway gateway;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(gateway.createPreference(any(), any(), any()))
                .thenAnswer(inv -> new PaymentPreferenceGateway.Preference("pref-" + inv.getArgument(0), "https://mp.test/pay/" + inv.getArgument(0)));
    }

    @AfterEach
    void cleanUp() {
        rateLimiterService.resetRateLimits();
        purchases.deleteAll();
        organizerRepository.deleteAll();
    }

    private ResultActions postCheckout(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/checkout").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> validBody() {
        return new java.util.LinkedHashMap<>(Map.of("email", "nueva@test.com", "password", STRONG_PASSWORD,
                "passwordConfirmation", STRONG_PASSWORD, "eventName", "Casamiento de Ana"));
    }

    private Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    // ---------- validaciones: ni MP ni la base ----------

    @Test
    @DisplayName("Email inválido, contraseñas distintas, contraseña débil o nombre vacío -> 400 con mensaje claro, sin llamar a MP ni guardar")
    void invalidInputNeverReachesMercadoPagoNorTheDatabase() throws Exception {
        Map<String, Object> badEmail = validBody();
        badEmail.put("email", "esto-no-es-un-email");
        postCheckout(badEmail).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Ingresá un email válido"));

        Map<String, Object> mismatch = validBody();
        mismatch.put("passwordConfirmation", "otra-clave-distinta-77");
        postCheckout(mismatch).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Las contraseñas no coinciden"));

        Map<String, Object> weak = validBody();
        weak.put("password", "12345678");
        weak.put("passwordConfirmation", "12345678");
        postCheckout(weak).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Esa contraseña es muy fácil de adivinar. Elegí otra"));

        Map<String, Object> noName = validBody();
        noName.put("eventName", "   ");
        postCheckout(noName).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Ingresá el nombre del evento"));

        Map<String, Object> tooLong = validBody();
        tooLong.put("password", "a".repeat(40) + "ñ".repeat(20));
        tooLong.put("passwordConfirmation", "a".repeat(40) + "ñ".repeat(20));
        postCheckout(tooLong).andExpect(status().isBadRequest());

        verify(gateway, never()).createPreference(any(), any(), any());
        assertThat(purchases.count()).isZero();
    }

    // ---------- compra nueva ----------

    @Test
    @DisplayName("Compra nueva: guarda la pendiente (email normalizado, solo el hash), la preferencia lleva su id y la respuesta trae el init_point")
    void newPurchaseIsRegisteredAndRedirectsToMercadoPago() throws Exception {
        Map<String, Object> body = validBody();
        body.put("email", "  Nueva@Test.COM ");
        String response = postCheckout(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<PendingPurchase> saved = purchases.findAll();
        assertThat(saved).hasSize(1);
        PendingPurchase purchase = saved.get(0);
        assertThat(purchase.getEmail()).isEqualTo("nueva@test.com");
        assertThat(purchase.getOrganizerId()).isNull();
        assertThat(purchase.getEventName()).isEqualTo("Casamiento de Ana");
        assertThat(purchase.getPasswordHash()).isNotEqualTo(STRONG_PASSWORD);
        assertThat(passwordEncoder.matches(STRONG_PASSWORD, purchase.getPasswordHash())).isTrue();
        assertThat(purchase.getMpPreferenceId()).isEqualTo("pref-" + purchase.getId());
        assertThat(response).contains("https://mp.test/pay/" + purchase.getId());
        assertThat(organizerRepository.count()).as("este bloque no crea cuentas").isZero();
    }

    @Test
    @DisplayName("Email que ya existe (otras mayúsculas / espacios) -> 409 con el mensaje de iniciar sesión, sin MP ni compra")
    void existingEmailIsRejectedWith409() throws Exception {
        organizerRepository.save(Organizer.builder().email("ya-existe@test.com").build());

        Map<String, Object> body = validBody();
        body.put("email", "  YA-Existe@Test.com ");
        postCheckout(body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya tenés una cuenta con este email. Iniciá sesión para crear un nuevo evento"));

        verify(gateway, never()).createPreference(any(), any(), any());
        assertThat(purchases.count()).isZero();
    }

    @Test
    @DisplayName("El monto sale de la configuración: un amount/price del cliente se ignora en la preferencia y en la compra")
    void amountComesFromConfigurationNotFromTheClient() throws Exception {
        Map<String, Object> body = validBody();
        body.put("amount", 1);
        body.put("price", "1");
        body.put("unitPrice", 1);
        postCheckout(body).andExpect(status().isOk());

        PendingPurchase purchase = purchases.findAll().get(0);
        assertThat(purchase.getAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        verify(gateway).createPreference(any(), org.mockito.ArgumentMatchers.argThat(a -> a.compareTo(new BigDecimal("50000")) == 0), any());
    }

    @Test
    @DisplayName("Rate limit de /api/v1/checkout: el intento 11 -> 429, y es un bucket propio (el login no queda bloqueado)")
    void checkoutRateLimitIsItsOwnBucket() throws Exception {
        Map<String, Object> invalid = validBody();
        invalid.put("email", "mal");
        for (int i = 1; i <= 10; i++) {
            postCheckout(invalid).andExpect(status().isBadRequest());
        }
        postCheckout(invalid).andExpect(status().isTooManyRequests());
        postCheckout(validBody()).andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@test.com\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Si Mercado Pago falla o no responde: 502 con mensaje claro y la compra pendiente no queda huérfana")
    void gatewayFailureLeavesNoOrphanPurchase() throws Exception {
        when(gateway.createPreference(any(), any(), any())).thenThrow(new PaymentGatewayException(new RuntimeException("timeout")));

        postCheckout(validBody()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(PaymentGatewayException.USER_MESSAGE));
        assertThat(purchases.count()).isZero();
    }

    @Test
    @DisplayName("Dos pedidos simultáneos (doble clic): dos compras pendientes distintas, sin error; la base no se rompe")
    void concurrentRequestsCreateIndependentPurchases() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> postCheckout(validBody()).andReturn().getResponse().getStatus();
            Future<Integer> a = pool.submit(call);
            Future<Integer> b = pool.submit(call);
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        List<PendingPurchase> saved = purchases.findAll();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(PendingPurchase::getId).doesNotHaveDuplicates();
        assertThat(saved).allSatisfy(p -> assertThat(p.getMpPreferenceId()).isNotNull());
    }

    // ---------- recompra ----------

    @Test
    @DisplayName("Recompra: la compra queda asociada al organizador autenticado y un organizerId/email del body se ignora")
    void repurchaseUsesThePrincipalAndIgnoresTheBody() throws Exception {
        Organizer me = organizerRepository.save(Organizer.builder().email("yo@test.com").build());
        Organizer other = organizerRepository.save(Organizer.builder().email("otro@test.com").build());

        mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookieOf(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventName", "Mi segundo evento", "organizerId", other.getId().toString(),
                                "email", "otro@test.com", "amount", 1))))
                .andExpect(status().isOk());

        PendingPurchase purchase = purchases.findAll().get(0);
        assertThat(purchase.getOrganizerId()).isEqualTo(me.getId());
        assertThat(purchase.getEmail()).isNull();
        assertThat(purchase.getPasswordHash()).isNull();
        assertThat(purchase.getAmount()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("Recompra sin sesión -> 401; con sesión de superadmin -> 403; sin nombre -> 400")
    void repurchaseRequiresAnOrganizerSession() throws Exception {
        mockMvc.perform(post("/api/v1/admin/checkout").contentType(MediaType.APPLICATION_JSON).content("{\"eventName\":\"X\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/checkout")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateSuperadminToken("superadmin@boda.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventName\":\"X\"}"))
                .andExpect(status().isForbidden());

        Organizer me = organizerRepository.save(Organizer.builder().email("yo2@test.com").build());
        mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookieOf(me)).contentType(MediaType.APPLICATION_JSON).content("{\"eventName\":\" \"}"))
                .andExpect(status().isBadRequest());

        verify(gateway, never()).createPreference(any(), any(), any());
        assertThat(purchases.count()).isZero();
    }

    @Test
    @DisplayName("Rate limit de la recompra: el intento 11 del mismo organizador -> 429; otro organizador no se ve afectado")
    void repurchaseRateLimitIsPerOrganizer() throws Exception {
        Organizer me = organizerRepository.save(Organizer.builder().email("limite@test.com").build());
        Organizer other = organizerRepository.save(Organizer.builder().email("limite2@test.com").build());
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookieOf(me)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventName\":\"Evento " + i + "\"}")).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookieOf(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventName\":\"Uno más\"}")).andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/v1/admin/checkout").cookie(cookieOf(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventName\":\"Otro\"}")).andExpect(status().isOk());
    }

    // ---------- logs ----------

    @Test
    @DisplayName("Ni la contraseña, ni el hash, ni el access token aparecen en los logs de un checkout; el external_reference sí, sin el email")
    void logsNeverContainSecretsAndNeverPairReferenceWithEmail() throws Exception {
        String email = "logs-privados@test.com";
        try (LogCapture logs = LogCapture.start()) {
            Map<String, Object> body = validBody();
            body.put("email", email);
            postCheckout(body).andExpect(status().isOk());

            when(gateway.createPreference(any(), any(), any())).thenThrow(new PaymentGatewayException(new RuntimeException("boom")));
            Map<String, Object> failing = validBody();
            failing.put("email", "otro-logs@test.com");
            postCheckout(failing).andExpect(status().isBadGateway());

            PendingPurchase purchase = purchases.findAll().get(0);
            String text = logs.text();
            assertThat(text).doesNotContain(STRONG_PASSWORD)
                    .doesNotContain(purchase.getPasswordHash())
                    .doesNotContain("TEST-token-falso-solo-para-tests");
            assertThat(text).contains(purchase.getId().toString());
            for (String line : text.split("\n")) {
                if (line.contains(purchase.getId().toString())) {
                    assertThat(line).as("el external_reference no va junto al email").doesNotContain(email);
                }
            }
        }
    }

    @Test
    @DisplayName("Con el interruptor prendido, el access token no sale en el log del arranque de la configuración")
    void settingsNeverLogTheAccessToken() {
        try (LogCapture logs = LogCapture.start()) {
            new CheckoutSettings(true, "50000", "APP_USR-token-super-secreto-123");
            assertThat(logs.text()).doesNotContain("APP_USR-token-super-secreto-123");
        }
    }

    @Test
    @DisplayName("UUID de la compra: aleatorio (versión 4), no secuencial")
    void purchaseIdsAreRandomUuids() throws Exception {
        postCheckout(validBody()).andExpect(status().isOk());
        rateLimiterService.resetRateLimits();
        postCheckout(validBody()).andExpect(status().isOk());
        List<UUID> ids = purchases.findAll().stream().map(PendingPurchase::getId).toList();
        assertThat(ids).allSatisfy(id -> assertThat(id.version()).isEqualTo(4));
    }
}
