package com.tuapp.eventfoto.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuapp.eventfoto.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.0: en la boda, las subidas que fallaban por tamaño o por un multipart cortado
 * caían en el handler genérico -> 500 con un mensaje técnico que upload.html mostraba
 * tal cual. Estos tests van contra un Tomcat REAL (no MockMvc, que no aplica los límites
 * de multipart ni el parseo real), para cubrir el camino que vio el invitado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UploadErrorResponsesIntegrationTest {

    private static final String BOUNDARY = "----EventFotoTestBoundary";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    @DisplayName("Un archivo de más de 30MB devuelve 413 JSON con 'La foto es demasiado pesada, probá con otra'")
    void oversizedFileReturnsFriendlyJson() throws Exception {
        byte[] body = multipartBody(new byte[31 * 1024 * 1024]);

        HttpResponse<String> response = client.send(uploadRequest(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("message").asText()).isEqualTo(GlobalExceptionHandler.UPLOAD_TOO_LARGE_MESSAGE);
        assertThat(json.get("status").asInt()).isEqualTo(413);
    }

    @Test
    @DisplayName("Un multipart malformado devuelve 400 JSON con 'Hubo un problema con la subida, intentá de nuevo'")
    void malformedMultipartReturnsFriendlyJson() throws Exception {
        // Declara multipart con boundary pero el body está cortado a mitad de una parte:
        // lo mismo que llega cuando un celular pierde señal en medio de la subida.
        byte[] truncated = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n"
                + "ÿØÿ datos cortados sin boundary de cierre").getBytes(StandardCharsets.ISO_8859_1);

        HttpResponse<String> response = client.send(uploadRequest(truncated), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("message").asText()).isEqualTo(GlobalExceptionHandler.UPLOAD_FAILED_MESSAGE);
    }

    private HttpRequest uploadRequest(byte[] body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events/evento-demo-k7m2xq9p/photos/upload-direct"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private byte[] multipartBody(byte[] fileBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 1024);
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"uploaderName\"\r\n\r\nTía Marta\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"guestToken\"\r\n\r\nguest-token-too-large\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"enorme.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
