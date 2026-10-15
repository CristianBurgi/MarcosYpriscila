package com.tuapp.eventfoto.common.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ResendEmailServiceTest {

    private static final String KEY = "re_clave_de_prueba_123";
    private static final String FROM = "EventFoto <hola@eventfoto.com.ar>";
    private static final EmailService.EmailMessage MESSAGE = new EmailService.EmailMessage(
            "persona@test.com", "Asunto", "<p>Hola</p>", "Hola", "ref-1");

    @Test
    @DisplayName("POST /emails con la key en Authorization y from, to, reply_to, subject, html y text")
    void sendsTheExpectedRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ResendEmailService.SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to[0]").value("persona@test.com"))
                .andExpect(jsonPath("$.reply_to[0]").value("respuestas@test.com"))
                .andExpect(jsonPath("$.subject").value("Asunto"))
                .andExpect(jsonPath("$.html").value("<p>Hola</p>"))
                .andExpect(jsonPath("$.text").value("Hola"))
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        new ResendEmailService(KEY, FROM, "respuestas@test.com", builder.build()).send(MESSAGE);
        server.verify();
    }

    @Test
    @DisplayName("Resend rechaza: excepción con el status y la referencia, sin la key, la dirección ni el cuerpo de Resend")
    void rejectionDoesNotLeakSecrets() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ResendEmailService.SEND_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid `to` field: persona@test.com\"}"));

        ResendEmailService service = new ResendEmailService(KEY, FROM, "respuestas@test.com", builder.build());
        assertThatThrownBy(() -> service.send(MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("422").hasMessageContaining("ref-1")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("persona@test.com"));
    }

    @Test
    @DisplayName("Sin RESEND_API_KEY no arranca: el mensaje nombra la variable")
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> new ResendEmailService(" ", FROM, "", RestClient.builder().build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
    }
}
