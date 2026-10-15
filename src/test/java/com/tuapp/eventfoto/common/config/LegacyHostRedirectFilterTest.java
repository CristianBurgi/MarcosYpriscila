package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyHostRedirectFilterTest {

    private static final String BASE = "https://canonical.test";
    private static final AppUrls APP_URLS = new AppUrls(BASE, "local");
    private static final LegacyHostRedirectFilter FILTER =
            new LegacyHostRedirectFilter(new String[] {" old.test", "WWW.Canonical.test "}, APP_URLS);

    @Test
    @DisplayName("Host viejo, GET o HEAD de página: 302 a la base con ruta y query intactas, sin caché")
    void redirectsPagesFromLegacyHost() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            MockHttpServletRequest request = request(method, "old.test", "/e/boda-x/album");
            request.setQueryString("a=1&b=%20dos");
            MockHttpServletResponse response = run(FILTER, request);

            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getHeader("Location")).isEqualTo(BASE + "/e/boda-x/album?a=1&b=%20dos");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    @DisplayName("www y hosts listados en mayúsculas (en la request o en la propiedad) también redirigen")
    void hostComparisonIgnoresCase() throws Exception {
        assertRedirected(run(FILTER, request("GET", "www.canonical.test", "/")), "/");
        assertRedirected(run(FILTER, request("GET", "OLD.TEST", "/admin/eventos")), "/admin/eventos");
    }

    @Test
    @DisplayName("Hosts no listados pasan: el canónico, uno interno de Railway y cualquier otro")
    void unlistedHostsPass() throws Exception {
        for (String host : new String[] {"canonical.test", "event-foto.railway.internal", "otro.test"}) {
            assertPassed(run(FILTER, request("GET", host, "/e/boda-x")));
        }
    }

    @Test
    @DisplayName("Lista vacía o en blanco: nada redirige (interruptor de apagado)")
    void emptyListDisablesFilter() throws Exception {
        for (String[] hosts : new String[][] {{}, {""}, {" ", " "}}) {
            assertPassed(run(new LegacyHostRedirectFilter(hosts, APP_URLS), request("GET", "old.test", "/e/boda-x")));
        }
    }

    @Test
    @DisplayName("Desde el host viejo no redirigen: POST/PUT, /api/**, /actuator/** ni estáticos")
    void excludedRequestsPass() throws Exception {
        assertPassed(run(FILTER, request("POST", "old.test", "/admin/login")));
        assertPassed(run(FILTER, request("PUT", "old.test", "/e/boda-x")));
        for (String path : new String[] {"/api/v1/events/boda-x", "/api/v1/events/boda-x/stream", "/api/v1/checkout/webhook",
                "/actuator/health", "/css/theme.css", "/js/event-context.js", "/img/demo/demo-01.webp", "/favicon.ico",
                "/uploads/x.jpg", "/photos/x.jpg"}) {
            assertPassed(run(FILTER, request("GET", "old.test", path)));
        }
    }

    @Test
    @DisplayName("Rutas que intentan cambiar de host (//x.com, /@x.com, codificadas, CRLF codificado) quedan bajo la base")
    void destinationAlwaysUnderBase() throws Exception {
        for (String path : new String[] {"//x.com", "//x.com/e/a", "/@x.com", "/%2F%2Fx.com", "/%5Cx.com", "/%0D%0ALocation:%20https://x.com"}) {
            MockHttpServletResponse response = run(FILTER, request("GET", "old.test", path));
            assertThat(response.getHeader("Location")).startsWith(BASE + "/").isEqualTo(BASE + path);
        }
    }

    private static MockHttpServletRequest request(String method, String host, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServerName(host);
        return request;
    }

    private static MockHttpServletResponse run(LegacyHostRedirectFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // Si redirigió, la cadena no siguió; si no, siguió sin tocar la respuesta.
        assertThat(chain.getRequest() == null).isEqualTo(response.getStatus() == 302);
        return response;
    }

    private static void assertRedirected(MockHttpServletResponse response, String path) {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo(BASE + path);
    }

    private static void assertPassed(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Location")).isNull();
    }
}
