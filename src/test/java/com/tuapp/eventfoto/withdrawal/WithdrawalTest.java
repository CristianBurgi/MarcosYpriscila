package com.tuapp.eventfoto.withdrawal;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.common.email.EmailService.EmailMessage;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Botón de arrepentimiento. Mismo contexto que SuperadminPageRequestsTest (solo EmailService mockeado): un contexto nuevo
 * vaciaría la H2 compartida. Cada test usa su propia IP para no gastar el cupo de los demás.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
    private static final String SCRIPT = "<script>alert(1)</script>";

    @Autowired private MockMvc mockMvc;
    @Autowired private WithdrawalRequestRepository repository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private EmailService emailService;
    @Value("${app.withdrawal.notify-to}") private String notifyTo;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        organizerRepository.deleteAll();
        rateLimiterService.resetRateLimits();
    }

    private static String newIp() {
        return "10.20.0." + NEXT_IP.getAndIncrement();
    }

    private ResultActions submit(String ip, String email, String eventName, String message, String honeypot) throws Exception {
        return mockMvc.perform(post("/arrepentimiento")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .param("email", email).param("eventName", eventName).param("message", message)
                .param(WithdrawalController.HONEYPOT, honeypot));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** La confirmación sin el número ni la hora, que cambian en cada pedido. */
    private static String normalized(String html) {
        return html.replaceAll("[0-9A-F]{8}", "NUMERO").replaceAll("\\d{2}/\\d{2}/\\d{4} a las \\d{2}:\\d{2}", "FECHA");
    }

    private List<EmailMessage> sentMails(int count) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("GET /arrepentimiento: 200 sin login, un solo h1 y los tres campos con etiqueta")
    void formIsPublic() throws Exception {
        Document page = Jsoup.parse(body(mockMvc.perform(get("/arrepentimiento")).andExpect(status().isOk())));
        assertThat(page.select("h1")).hasSize(1).first().extracting(h -> h.text()).isEqualTo("Botón de arrepentimiento");
        assertThat(page.select("html").attr("lang")).isEqualTo("es-AR");
        assertThat(page.text()).contains("podés revocar la compra dentro de los 10 días corridos desde la contratación");
        for (String field : List.of("email", "eventName", "message")) {
            assertThat(page.select("label[for=" + field + "]")).hasSize(1);
        }
        assertThat(page.select("form[method=post][action=/arrepentimiento]")).hasSize(1);
    }

    @Test
    @DisplayName("POST válido: guarda el pedido, manda el aviso a notify-to y el recibo al cliente, y muestra número y fecha")
    void validRequestIsSavedAndMailed() throws Exception {
        Document page = Jsoup.parse(body(submit(newIp(), "cliente@ejemplo.com", "Boda de Ana", "Quiero revocar", "")
                .andExpect(status().isOk())));

        List<WithdrawalRequest> saved = repository.findAll();
        assertThat(saved).hasSize(1);
        WithdrawalRequest request = saved.get(0);
        assertThat(request.getEmail()).isEqualTo("cliente@ejemplo.com");
        assertThat(request.getEventName()).isEqualTo("Boda de Ana");
        assertThat(request.getMessage()).isEqualTo("Quiero revocar");
        assertThat(request.getStatus()).isEqualTo(WithdrawalRequest.Status.RECEIVED);
        assertThat(request.getHandledAt()).isNull();

        assertThat(page.select("h1").text()).isEqualTo("Recibimos tu pedido");
        assertThat(page.select(".number").text()).isEqualTo(request.getNumber());
        assertThat(page.text()).containsPattern("\\d{2}/\\d{2}/\\d{4} a las \\d{2}:\\d{2} \\(hora de Argentina\\)");

        List<EmailMessage> mails = sentMails(2);
        assertThat(mails).extracting(EmailMessage::to).containsExactly(notifyTo, "cliente@ejemplo.com");
        assertThat(mails.get(0).textBody()).contains(request.getNumber(), "cliente@ejemplo.com", "Boda de Ana", "Quiero revocar");
        assertThat(mails.get(1).textBody()).contains(request.getNumber(), "(hora de Argentina)", "Si no hiciste este pedido");
    }

    @Test
    @DisplayName("Los asuntos de los mails no llevan texto de la persona")
    void subjectsHaveNoUserText() throws Exception {
        submit(newIp(), "asunto@ejemplo.com", "EventoUnico123", "MensajeUnico456", "").andExpect(status().isOk());
        for (EmailMessage mail : sentMails(2)) {
            assertThat(mail.subject()).doesNotContain("asunto", "ejemplo", "EventoUnico123", "MensajeUnico456");
        }
    }

    @Test
    @DisplayName("POST inválido: 400 con los errores en la página, sin guardar ni mandar mails")
    void invalidRequestIsRejected() throws Exception {
        String ip = newIp();
        Document page = Jsoup.parse(body(submit(ip, "no-es-un-email", "", "x".repeat(1001), "").andExpect(status().isBadRequest())));
        assertThat(page.select("#email-error").text()).isEqualTo("Revisá el email: no parece válido.");
        assertThat(page.select("#eventName-error").text()).isEqualTo("Escribí el nombre del evento.");
        assertThat(page.select("#message-error").text()).contains("1000");
        assertThat(page.select("#email").attr("aria-invalid")).isEqualTo("true");
        assertThat(page.select("#email").attr("aria-describedby")).isEqualTo("email-error");
        assertThat(page.select("[role=alert]")).isNotEmpty();
        assertThat(page.select("#email").val()).isEqualTo("no-es-un-email");

        submit(ip, "a@b.co", "x".repeat(101), "", "").andExpect(status().isBadRequest());
        submit(ip, "", "Evento", "", "").andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("Misma respuesta para un email con cuenta y uno sin cuenta")
    void sameResponseWithOrWithoutAccount() throws Exception {
        organizerRepository.save(Organizer.builder().email("con-cuenta@ejemplo.com").build());

        ResultActions withAccount = submit(newIp(), "con-cuenta@ejemplo.com", "Evento", "Hola", "").andExpect(status().isOk());
        ResultActions withoutAccount = submit(newIp(), "sin-cuenta@ejemplo.com", "Evento", "Hola", "").andExpect(status().isOk());

        assertThat(normalized(body(withAccount))).isEqualTo(normalized(body(withoutAccount)));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Honeypot completo: no guarda ni manda nada, y el número de la confirmación no existe en la base")
    void honeypotSavesNothing() throws Exception {
        submit(newIp(), "real@ejemplo.com", "Evento real", "", "").andExpect(status().isOk());
        String realNumber = repository.findAll().get(0).getNumber();
        org.mockito.Mockito.clearInvocations(emailService);

        Document page = Jsoup.parse(body(submit(newIp(), "bot@ejemplo.com", "Evento", "spam", "https://spam.example")
                .andExpect(status().isOk())));

        assertThat(repository.count()).isEqualTo(1);
        verify(emailService, never()).send(any());
        String fakeNumber = page.select(".number").text();
        assertThat(fakeNumber).matches("[0-9A-F]{8}").isNotEqualTo(realNumber);
        assertThat(repository.findAll()).noneMatch(r -> r.getNumber().equals(fakeNumber));
    }

    @Test
    @DisplayName("Si EmailService falla, el pedido queda guardado y la persona ve la confirmación")
    void mailFailureKeepsTheRequest() throws Exception {
        doThrow(new IllegalStateException("Resend caído")).when(emailService).send(any());

        Document page = Jsoup.parse(body(submit(newIp(), "falla@ejemplo.com", "Evento", "", "").andExpect(status().isOk())));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(page.select(".number").text()).isEqualTo(repository.findAll().get(0).getNumber());
        verify(emailService, times(2)).send(any());
    }

    @Test
    @DisplayName("Un recibo por email por hora: el segundo pedido se guarda y avisa a EventFoto, sin recibo y con la misma respuesta")
    void oneReceiptPerEmailPerHour() throws Exception {
        String first = body(submit(newIp(), "Repetido@Ejemplo.com", "Evento", "Hola", "").andExpect(status().isOk()));
        String second = body(submit(newIp(), "repetido@ejemplo.com", "Evento", "Hola", "").andExpect(status().isOk()));

        assertThat(repository.count()).isEqualTo(2);
        List<EmailMessage> mails = sentMails(3);
        assertThat(mails).extracting(EmailMessage::to).containsExactly(notifyTo, "Repetido@Ejemplo.com", notifyTo);
        assertThat(normalized(second)).isEqualTo(normalized(first));
    }

    @Test
    @DisplayName("Más de 5 envíos por IP en 15 minutos: 429 con el mensaje en la página y sin guardar")
    void rateLimitedPerIp() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            submit(ip, "", "", "", "").andExpect(status().isBadRequest());
        }
        Document page = Jsoup.parse(body(submit(ip, "limite@ejemplo.com", "Evento", "", "").andExpect(status().isTooManyRequests())));
        assertThat(page.select("[role=alert]").text()).isEqualTo("Demasiados intentos, probá en unos minutos.");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("<script> en el mensaje y el evento sale escapado en los dos mails HTML, el superadmin y las páginas")
    void htmlIsEscapedEverywhere() throws Exception {
        String confirmation = body(submit(newIp(), "escape@ejemplo.com", "Evento " + SCRIPT, "Mensaje " + SCRIPT, "")
                .andExpect(status().isOk()));
        String formWithErrors = body(submit(newIp(), "no-es-email", "Evento " + SCRIPT, "Mensaje " + SCRIPT, "")
                .andExpect(status().isBadRequest()));

        assertThat(repository.findAll().get(0).getMessage()).isEqualTo("Mensaje " + SCRIPT);
        List<EmailMessage> mails = sentMails(2);
        assertThat(mails.get(0).htmlBody()).doesNotContain(SCRIPT).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(mails.get(1).htmlBody()).doesNotContain(SCRIPT);
        assertThat(confirmation).doesNotContain(SCRIPT);
        assertThat(formWithErrors).doesNotContain(SCRIPT).contains("&lt;script&gt;");

        Cookie superadmin = new Cookie(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME,
                jwtTokenProvider.generateSuperadminToken("superadmin@boda.com"));
        String panel = body(mockMvc.perform(get("/superadmin/eventos").cookie(superadmin)).andExpect(status().isOk()));
        Document panelPage = Jsoup.parse(panel);
        assertThat(panel).doesNotContain(SCRIPT);
        assertThat(panelPage.select("#arrepentimiento td").text()).contains("Evento " + SCRIPT, "Mensaje " + SCRIPT, "RECEIVED");
    }

    @Test
    @DisplayName("El footer de la landing enlaza al botón de arrepentimiento")
    void landingFooterLink() throws Exception {
        Document landing = Jsoup.parse(body(mockMvc.perform(get("/")).andExpect(status().isOk())));
        assertThat(landing.select("footer a[href=/arrepentimiento]").text()).isEqualTo("Botón de arrepentimiento");
    }

    @Test
    @DisplayName("Textos legales: los tres cambios exactos")
    void legalTextsMentionTheButton() throws Exception {
        Document terms = Jsoup.parse(body(mockMvc.perform(get("/terminos")).andExpect(status().isOk())));
        assertThat(terms.select("#pagos-y-reembolsos li").text()).contains("podés revocar dentro de los 10 días corridos desde la "
                + "contratación con el botón de arrepentimiento del sitio o escribiendo a hola@eventfoto.com.ar, y te devolvemos lo pagado.");
        assertThat(terms.select("#pagos-y-reembolsos a[href=/arrepentimiento]").text()).isEqualTo("botón de arrepentimiento del sitio");

        Document privacy = Jsoup.parse(body(mockMvc.perform(get("/privacidad")).andExpect(status().isOk())));
        assertThat(privacy.select("#datos-que-recopilamos li").eachText())
                .contains("Pedido de arrepentimiento: tu email, el nombre del evento y el mensaje que escribas.");
        assertThat(privacy.select("#cuanto-los-conservamos li").eachText())
                .contains("Pedidos de arrepentimiento: el tiempo necesario para gestionarlos y atender reclamos.");
    }
}
