package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ClientIpResolver es la única fuente de la IP del cliente para rate limiting (antes
 * había 5 copias de esta lógica, cada una confiando ciegamente en el primer valor de
 * X-Forwarded-For -- un header que arma el cliente).
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    @DisplayName("Sin proxy conocido en el medio (remoteAddr fuera del rango interno de Railway): se ignora X-Forwarded-For y se usa remoteAddr")
    void ignoresForwardedHeaderWhenRemoteAddrIsNotATrustedProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Un X-Forwarded-For inventado no cambia la IP resuelta si quien conecta no es un proxy interno conocido")
    void fabricatedForwardedHeaderDoesNotChangeResolvedIpWithoutTrustedProxy() {
        HttpServletRequest attempt1 = mock(HttpServletRequest.class);
        when(attempt1.getRemoteAddr()).thenReturn("203.0.113.9");
        when(attempt1.getHeader("X-Forwarded-For")).thenReturn("6.6.6.6");

        HttpServletRequest attempt2 = mock(HttpServletRequest.class);
        when(attempt2.getRemoteAddr()).thenReturn("203.0.113.9");
        when(attempt2.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9"); // el atacante cambia el header en cada intento

        // Misma conexión real (mismo remoteAddr) -> misma IP resuelta, pase lo que pase en el header:
        // el rate limiter va a tratar ambos intentos como el mismo cliente.
        assertThat(resolver.resolve(attempt1)).isEqualTo(resolver.resolve(attempt2)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("Con un hop interno de Railway (100.64.0.0/10) como remoteAddr, se toma la IP real del cliente del header")
    void trustsForwardedHeaderWhenRemoteAddrIsRailwayInternalProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.5"); // hop interno de Railway
        when(request.getHeader("X-Forwarded-For")).thenReturn("200.45.12.9");

        assertThat(resolver.resolve(request)).isEqualTo("200.45.12.9");
    }

    @Test
    @DisplayName("Con varios hops internos encadenados, se toma la primera IP (de derecha a izquierda) que no sea un hop interno")
    void skipsMultipleInternalHopsToFindRealClientIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.10.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("200.45.12.9, 100.64.3.2, 100.64.10.1");

        assertThat(resolver.resolve(request)).isEqualTo("200.45.12.9");
    }

    @Test
    @DisplayName("Sin header X-Forwarded-For, siempre se usa remoteAddr")
    void fallsBackToRemoteAddrWithoutHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertThat(resolver.resolve(request)).isEqualTo("100.64.0.5");
    }
}
