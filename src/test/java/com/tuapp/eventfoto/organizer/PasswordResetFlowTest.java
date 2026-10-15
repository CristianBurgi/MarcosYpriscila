package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.testsupport.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.2-B: recuperación de contraseña del organizador, de punta a punta (pedido, mail, link, contraseña nueva).
 * Los mails corren en el mismo hilo en los tests (app.mail.async=false) y EmailService está mockeado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class PasswordResetFlowTest {

    private static final String EMAIL = "recupera@test.com";
    private static final String OLD_PASSWORD = "vieja-clave-larga-41";
    private static final String NEW_PASSWORD = "nueva-clave-larga-73";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizers;
    @Autowired private OrganizerTokenRepository tokens;
    @Autowired private OrganizerTokenService organizerTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private AppUrls appUrls;
    @Autowired private MutableClock clock;
    @MockBean private EmailService emailService;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
        rateLimiterService.resetRateLimits();
        tokens.deleteAll();
        organizers.findByEmailIgnoreCase(EMAIL).ifPresent(organizers::delete);
        organizers.findByEmailIgnoreCase("sin-activar@test.com").ifPresent(organizers::delete);
        organizer = organizers.save(Organizer.builder().email(EMAIL).passwordHash(passwordEncoder.encode(OLD_PASSWORD)).build());
    }

    @Test
    @DisplayName("Email con cuenta, sin cuenta y con cuenta sin activar: exactamente la misma respuesta; mail solo al primero")
    void sameResponseWhetherOrNotTheAccountExists() throws Exception {
        organizers.save(Organizer.builder().email("sin-activar@test.com").build());

        MvcResult existing = requestReset(EMAIL).andReturn();
        MvcResult missing = requestReset("nadie@test.com").andReturn();
        MvcResult unactivated = requestReset("sin-activar@test.com").andReturn();

        for (MvcResult other : List.of(missing, unactivated)) {
            assertThat(other.getResponse().getStatus()).isEqualTo(existing.getResponse().getStatus()).isEqualTo(202);
            assertThat(other.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(existing.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(other.getResponse().getHeaderNames()).isEqualTo(existing.getResponse().getHeaderNames());
        }
        ArgumentCaptor<EmailService.EmailMessage> sent = ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService, times(1)).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("Link del mail -> contraseña nueva: el login viejo deja de andar, el nuevo anda y la sesión abierta da 401")
    void resetChangesPasswordAndKillsOldSessions() throws Exception {
        Cookie oldSession = login(OLD_PASSWORD);
        // Sesión válida: el slug no existe, así que pasa la autenticación y cae en 404.
        mockMvc.perform(get("/api/v1/admin/events/no-existe-zzzz/photos").cookie(oldSession)).andExpect(status().isNotFound());

        String token = requestAndCaptureToken();
        mockMvc.perform(confirm(token, NEW_PASSWORD)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/events/no-existe-zzzz/photos").cookie(oldSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest(OLD_PASSWORD)).andExpect(status().isUnauthorized());
        login(NEW_PASSWORD);
    }

    @Test
    @DisplayName("El mail trae HTML y texto plano con el link de APP_BASE_URL")
    void mailHasHtmlAndPlainTextWithTheLink() throws Exception {
        requestReset(EMAIL).andExpect(status().isAccepted());
        ArgumentCaptor<EmailService.EmailMessage> sent = ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService).send(sent.capture());
        String link = extractLink(sent.getValue().textBody());

        assertThat(link).startsWith(appUrls.baseUrl() + "/restablecer-contrasena?token=");
        assertThat(sent.getValue().htmlBody()).contains(link.replace("&", "&amp;")).contains("vence en 1 hora");
        assertThat(sent.getValue().textBody()).doesNotContain("<").contains("vence en 1 hora");
    }

    @Test
    @DisplayName("Link usado, vencido (más de 1 hora), inválido o de activación: rechazado con el mismo mensaje")
    void usedExpiredInvalidOrWrongPurposeTokenIsRejected() throws Exception {
        String used = requestAndCaptureToken();
        mockMvc.perform(confirm(used, NEW_PASSWORD)).andExpect(status().isOk());
        String usedMessage = mockMvc.perform(confirm(used, "otra-clave-larga-55")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String expiring = requestAndCaptureToken();
        clock.set(clock.instant().plus(Duration.ofMinutes(61)));
        mockMvc.perform(confirm(expiring, "otra-clave-larga-55")).andExpect(status().isBadRequest());

        mockMvc.perform(confirm("no-es-un-token", "otra-clave-larga-55")).andExpect(status().isBadRequest());

        String activation = organizerTokenService.issue(organizer, OrganizerTokenPurpose.ACCOUNT_ACTIVATION).rawToken();
        mockMvc.perform(confirm(activation, "otra-clave-larga-55")).andExpect(status().isBadRequest());

        assertThat(usedMessage).contains("El link no es válido, ya se usó o venció.");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, organizers.findById(organizer.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Un link de recuperación no sirve para activar una cuenta")
    void resetTokenDoesNotActivate() throws Exception {
        String reset = requestAndCaptureToken();
        mockMvc.perform(post("/api/v1/organizer/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + reset + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(confirm(reset, NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Pedir otro link invalida el anterior")
    void newRequestInvalidatesThePreviousLink() throws Exception {
        String first = requestAndCaptureToken();
        String second = requestAndCaptureToken();

        mockMvc.perform(confirm(first, NEW_PASSWORD)).andExpect(status().isBadRequest());
        mockMvc.perform(confirm(second, NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una contraseña rechazada por la política (incluida la igual al email) no gasta el link")
    void rejectedPasswordDoesNotConsumeTheLink() throws Exception {
        String token = requestAndCaptureToken();
        mockMvc.perform(confirm(token, "12345678")).andExpect(status().isBadRequest());
        mockMvc.perform(confirm(token, EMAIL)).andExpect(status().isBadRequest());
        mockMvc.perform(confirm(token, NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Rate limit del pedido: 3 por email por hora y 5 por IP cada 15 minutos; el 429 no revela si la cuenta existe")
    void requestIsRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            requestReset("nadie@test.com").andExpect(status().isAccepted());
        }
        String limited = requestReset("nadie@test.com").andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        rateLimiterService.resetRateLimits();
        for (int i = 0; i < 3; i++) {
            requestReset(EMAIL).andExpect(status().isAccepted());
        }
        String limitedExisting = requestReset(EMAIL).andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(messageOf(limitedExisting)).isEqualTo(messageOf(limited));

        rateLimiterService.resetRateLimits();
        for (int i = 0; i < 5; i++) {
            requestReset("ip" + i + "@test.com").andExpect(status().isAccepted());
        }
        requestReset("ip5@test.com").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Rate limit de la confirmación: 10 por IP cada 15 minutos")
    void confirmIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(confirm("token-" + i, NEW_PASSWORD)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(confirm("token-10", NEW_PASSWORD)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Páginas: /admin/recuperar sin sesión; el link y /activar-cuenta sin caché ni referrer")
    void pagesAreReachableAndLinkPagesAreNotCached() throws Exception {
        mockMvc.perform(get("/admin/recuperar")).andExpect(status().isOk());
        for (String path : List.of("/restablecer-contrasena?token=x", "/activar-cuenta?token=x")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
        verify(emailService, never()).send(any());
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/organizer/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private org.springframework.test.web.servlet.RequestBuilder confirm(String token, String password) {
        return post("/api/v1/organizer/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}");
    }

    private String requestAndCaptureToken() throws Exception {
        org.mockito.Mockito.clearInvocations(emailService);
        requestReset(EMAIL).andExpect(status().isAccepted());
        ArgumentCaptor<EmailService.EmailMessage> sent = ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService).send(sent.capture());
        String link = extractLink(sent.getValue().textBody());
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private static String extractLink(String text) {
        return text.lines().map(String::trim).filter(line -> line.startsWith("http")).findFirst().orElseThrow();
    }

    private static String messageOf(String json) {
        return json.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1");
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(String password) {
        return post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }

    private Cookie login(String password) throws Exception {
        rateLimiterService.resetRateLimits();
        Cookie cookie = mockMvc.perform(loginRequest(password)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
