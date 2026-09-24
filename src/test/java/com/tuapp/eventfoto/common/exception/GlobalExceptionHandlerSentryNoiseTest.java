package com.tuapp.eventfoto.common.exception;

import io.sentry.Sentry;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Fase 9.0: ruido de Sentry de la boda del 19/09. Una desconexión normal de un cliente
 * SSE y un favicon inexistente no son errores del servidor y no pueden llegar a Sentry;
 * un error real sí.
 */
class GlobalExceptionHandlerSentryNoiseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events/marcos-y-priscila/stream");

    @Test
    @DisplayName("ClientAbortException (el cliente cortó mientras se le escribía, ej. cancela el ZIP) no se reporta a Sentry")
    void clientAbortIsNotReported() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response = handler.handleGenericException(
                    new ClientAbortException(new IOException("Broken pipe")), request);

            assertThat(response).isNull();
            sentry.verifyNoInteractions();
        }
    }

    /**
     * Fallas del lado del SERVIDOR (lectura de R2, conexión a la base) con los mismos
     * mensajes/tipos que una desconexión de cliente. Fuera de un dispatch ASYNC son
     * errores reales: 500 + Sentry. (DisconnectedClientHelper las habría silenciado.)
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("serverSideFailuresThatLookLikeDisconnects")
    @DisplayName("Una falla leyendo de R2/BD con mensaje 'Connection reset'/'Broken pipe' o EOFException SÍ va a Sentry")
    void serverSideReadFailuresAreStillReported(String description, Exception ex) {
        MockHttpServletRequest zipDownload = new MockHttpServletRequest("GET", "/api/v1/admin/photos/download-zip");

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response = handler.handleGenericException(ex, zipDownload);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            sentry.verify(() -> Sentry.captureException(ex));
        }
    }

    static Stream<Arguments> serverSideFailuresThatLookLikeDisconnects() {
        return Stream.of(
                Arguments.of("IOException 'Connection reset' leyendo de R2",
                        new IOException("Connection reset")),
                Arguments.of("EOFException: objeto de R2 truncado",
                        new EOFException("Unexpected end of stream")),
                Arguments.of("RuntimeException del SDK/driver causada por SocketException 'Connection reset'",
                        new IllegalStateException("Unable to execute HTTP request", new SocketException("Connection reset"))),
                Arguments.of("IOException 'Broken pipe' fuera de un stream SSE",
                        new IOException("Broken pipe"))
        );
    }

    @Test
    @DisplayName("IOException con mensaje localizado durante el async dispatch del SSE (reproducido en Windows) no va a Sentry")
    void localizedDisconnectDuringAsyncDispatchIsNotReported() {
        MockHttpServletRequest asyncDispatch = new MockHttpServletRequest("GET", "/api/v1/events/marcos-y-priscila/stream");
        asyncDispatch.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response = handler.handleGenericException(
                    new IOException("Se ha anulado una conexión establecida por el software en su equipo host."), asyncDispatch);

            assertThat(response).isNull();
            sentry.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("AsyncRequestNotUsableException (async error dispatch del SSE) no se reporta a Sentry")
    void asyncRequestNotUsableIsNotReported() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            handler.handleClientDisconnected(
                    new AsyncRequestNotUsableException("ServletOutputStream failed to write: java.io.IOException: Broken pipe"), request);

            sentry.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("NoResourceFoundException (favicon, bots) devuelve 404 sin Sentry")
    void missingStaticResourceIsA404WithoutSentry() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response =
                    handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "wp-login.php"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            sentry.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("Una excepción 4xx de Spring MVC (método no soportado) respeta su status y no va a Sentry")
    void springClientErrorKeepsItsStatusWithoutSentry() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response = handler.handleGenericException(
                    new org.springframework.web.HttpRequestMethodNotSupportedException("POST"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(405);
            sentry.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("Control: un error real del servidor SÍ se reporta a Sentry con 500")
    void realErrorIsStillReported() {
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<ErrorResponseDTO> response = handler.handleGenericException(new IllegalStateException("boom"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            sentry.verify(() -> Sentry.captureException(any(Throwable.class)));
        }
    }
}
