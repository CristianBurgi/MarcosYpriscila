package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regla confirmada contra producción real (30/09/2026, ver ClientIpResolver): Railway
 * REEMPLAZA X-Real-IP con la IP real del cliente cuando remoteAddr es uno de sus hops
 * internos (100.64.0.0/10) -- Forwarded y X-Envoy-External-Address, en cambio, dejan
 * pasar lo que manda el cliente tal cual, así que nunca se usan acá.
 */
class ClientIpResolverTest {

    private static final String SECRET = "secreto-de-prueba";

    private final ClientIpResolver resolver = new ClientIpResolver("");
    private final ClientIpResolver cfResolver = new ClientIpResolver(SECRET);

    private static HttpServletRequest cfRequest(String remoteAddr, String originAuth, String cfIp) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Real-IP")).thenReturn("172.70.1.1"); // edge de Cloudflare según Railway
        when(request.getHeader("X-Origin-Auth")).thenReturn(originAuth);
        when(request.getHeader("CF-Connecting-IP")).thenReturn(cfIp);
        return request;
    }

    @Test
    @DisplayName("CF: secreto correcto + CF-Connecting-IP válida -> se usa CF-Connecting-IP")
    void correctSecretUsesCfIp() {
        assertThat(cfResolver.resolve(cfRequest("100.64.0.2", SECRET, "186.122.212.179"))).isEqualTo("186.122.212.179");
    }

    @Test
    @DisplayName("CF: secreto incorrecto o ausente -> se ignora CF-Connecting-IP y cae a X-Real-IP")
    void wrongOrMissingSecretIgnoresCfIp() {
        assertThat(cfResolver.resolve(cfRequest("100.64.0.2", "otro", "6.6.6.6"))).isEqualTo("172.70.1.1");
        assertThat(cfResolver.resolve(cfRequest("100.64.0.2", null, "6.6.6.6"))).isEqualTo("172.70.1.1");
    }

    @Test
    @DisplayName("CF: secreto correcto + CF-Connecting-IP basura -> cae a X-Real-IP")
    void correctSecretWithGarbageCfIpFallsBack() {
        assertThat(cfResolver.resolve(cfRequest("100.64.0.2", SECRET, "no-soy-una-ip"))).isEqualTo("172.70.1.1");
    }

    @Test
    @DisplayName("CF: variable sin configurar + header y CF-Connecting-IP presentes -> comportamiento actual")
    void unconfiguredSecretNeverTrustsCfIp() {
        assertThat(resolver.resolve(cfRequest("100.64.0.2", SECRET, "6.6.6.6"))).isEqualTo("172.70.1.1");
        assertThat(resolver.resolve(cfRequest("100.64.0.2", "", "6.6.6.6"))).isEqualTo("172.70.1.1");
    }

    @Test
    @DisplayName("CF: remoteAddr fuera de 100.64.0.0/10 -> remoteAddr aunque vengan todos los headers")
    void untrustedRemoteAddrIgnoresCfHeaders() {
        assertThat(cfResolver.resolve(cfRequest("203.0.113.9", SECRET, "6.6.6.6"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("remoteAddr interno (hop de Railway) + X-Real-IP válido: se usa X-Real-IP")
    void trustedProxyWithValidRealIpUsesRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.2");
        when(request.getHeader("X-Real-IP")).thenReturn("186.122.212.179");

        assertThat(resolver.resolve(request)).isEqualTo("186.122.212.179");
    }

    @Test
    @DisplayName("remoteAddr NO interno: se ignoran los headers (aunque estén inventados) y se usa remoteAddr")
    void untrustedRemoteAddrIgnoresHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(request.getHeader("X-Real-IP")).thenReturn("6.6.6.6"); // inventado por el cliente

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("Dos intentos con el mismo remoteAddr no interno pero X-Real-IP distinto en cada uno: mismo resultado")
    void fabricatedRealIpHeaderDoesNotChangeResolvedIpWithoutTrustedProxy() {
        HttpServletRequest attempt1 = mock(HttpServletRequest.class);
        when(attempt1.getRemoteAddr()).thenReturn("203.0.113.9");
        when(attempt1.getHeader("X-Real-IP")).thenReturn("6.6.6.6");

        HttpServletRequest attempt2 = mock(HttpServletRequest.class);
        when(attempt2.getRemoteAddr()).thenReturn("203.0.113.9");
        when(attempt2.getHeader("X-Real-IP")).thenReturn("9.9.9.9");

        assertThat(resolver.resolve(attempt1)).isEqualTo(resolver.resolve(attempt2)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("remoteAddr interno pero sin X-Real-IP: cae a remoteAddr (lado seguro del fallo)")
    void trustedProxyWithoutRealIpFallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.2");
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        assertThat(resolver.resolve(request)).isEqualTo("100.64.0.2");
    }

    @Test
    @DisplayName("remoteAddr interno pero X-Real-IP es basura (no es una IP): cae a remoteAddr")
    void trustedProxyWithGarbageRealIpFallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.2");
        when(request.getHeader("X-Real-IP")).thenReturn("no-soy-una-ip");

        assertThat(resolver.resolve(request)).isEqualTo("100.64.0.2");
    }

    @Test
    @DisplayName("X-Real-IP en formato IPv6 válido también se acepta")
    void acceptsValidIpv6RealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("100.64.0.2");
        when(request.getHeader("X-Real-IP")).thenReturn("2001:db8::1");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("isValidIpLiteral: acepta IPv4/IPv6 bien formadas y rechaza basura, vacío y hostnames")
    void isValidIpLiteralValidatesFormat() {
        assertThat(ClientIpResolver.isValidIpLiteral("186.122.212.179")).isTrue();
        assertThat(ClientIpResolver.isValidIpLiteral("2001:db8::1")).isTrue();
        assertThat(ClientIpResolver.isValidIpLiteral("::1")).isTrue();
        assertThat(ClientIpResolver.isValidIpLiteral("")).isFalse();
        assertThat(ClientIpResolver.isValidIpLiteral(null)).isFalse();
        assertThat(ClientIpResolver.isValidIpLiteral("no-soy-una-ip")).isFalse();
        assertThat(ClientIpResolver.isValidIpLiteral("999.999.999.999")).isFalse();
        assertThat(ClientIpResolver.isValidIpLiteral("evil.com")).isFalse();
    }
}
