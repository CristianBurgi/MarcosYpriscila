package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los redirects salen con Location relativo (server.tomcat.use-relative-redirects): el host y el esquema de la
 * request no se filtran a la respuesta. MockMvc no pasa por Tomcat, por eso este test levanta el servidor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RelativeRedirectsTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient(); // no sigue redirects por defecto

    @Test
    @DisplayName("Sin sesión, /admin/eventos y /superadmin/eventos redirigen al login con Location relativo")
    void loginRedirectsAreRelative() throws Exception {
        assertThat(location("/admin/eventos")).isEqualTo("/admin/login");
        assertThat(location("/superadmin/eventos")).isEqualTo("/superadmin/login");
    }

    private String location(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }
}
