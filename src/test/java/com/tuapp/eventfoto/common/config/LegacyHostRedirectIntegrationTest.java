package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El filtro registrado de verdad: antes de Spring Security (redirige sin pedir login) y detrás de Tomcat, que entrega
 * el host sin puerto y rechaza la barra invertida. Con socket crudo porque HttpClient no deja fijar el header Host.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.base-url=https://canonical.test", "app.legacy-hosts=old.test,www.canonical.test"})
class LegacyHostRedirectIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @LocalServerPort private int port;

    @Test
    @DisplayName("Panel sin sesión desde el host viejo: 302 al host canónico, no al login (corre antes de Security)")
    void redirectsBeforeSecurity() throws Exception {
        mockMvc.perform(get("/admin/eventos").with(r -> { r.setServerName("old.test"); return r; }))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://canonical.test/admin/eventos"));
    }

    @Test
    @DisplayName("Tomcat real: host listado con puerto y en mayúsculas redirige; el canónico pasa; la barra invertida da 400")
    void realTomcat() throws Exception {
        String redirected = raw("GET /e/boda-x/album?a=1 HTTP/1.1", "OLD.TEST:8443");
        assertThat(redirected).startsWith("HTTP/1.1 302")
                .containsIgnoringCase("location: https://canonical.test/e/boda-x/album?a=1\r\n");

        assertThat(raw("HEAD / HTTP/1.1", "www.canonical.test")).startsWith("HTTP/1.1 302");
        assertThat(raw("GET / HTTP/1.1", "canonical.test")).startsWith("HTTP/1.1 200");
        assertThat(raw("GET /\\x.com HTTP/1.1", "old.test")).startsWith("HTTP/1.1 400");
    }

    private String raw(String requestLine, String host) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
