package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Fase 9.7-A: ninguna ruta del superadmin queda sin el rol. Recorre TODAS las rutas del mapping bajo /superadmin y
 * /api/v1/superadmin (sin lista escrita a mano; solo los dos logins quedan afuera) y exige, en cada ruta y método:
 * <ol>
 *   <li>sin sesión: la API responde 401 y la vista redirige al login del superadmin;</li>
 *   <li>con el JWT de un organizador válido: 403 (con su cookie, que en estas rutas no se lee: igual que sin sesión);</li>
 *   <li>con el JWT del superadmin: ni 401 ni 403;</li>
 *   <li>cada POST, con la sesión del superadmin pero sin Content-Type JSON (un formulario de otro sitio): 415.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuperadminRouteEnumerationTest {

    private static final Set<String> PUBLIC = Set.of("/superadmin/login", "/api/v1/superadmin/auth/login");
    private static final List<RequestMethod> ALL_METHODS =
            List.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private OrganizerRepository organizerRepository;
    @MockBean private EmailService emailService;

    private String organizerToken;
    private String superadminToken;

    private record Route(String pattern, RequestMethod method) {
        boolean isApi() {
            return pattern.startsWith("/api/");
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    @BeforeEach
    void setUp() {
        Organizer organizer = organizerRepository.save(Organizer.builder().email("enum-superadmin@test.com").passwordHash("x").build());
        organizerToken = jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());
        superadminToken = jwtTokenProvider.generateSuperadminToken("superadmin@boda.com");
    }

    @AfterEach
    void cleanUp() {
        organizerRepository.findByEmailIgnoreCase("enum-superadmin@test.com").ifPresent(organizerRepository::delete);
    }

    private List<Route> superadminRoutes() {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null ? info.getPathPatternsCondition().getPatternValues() : Set.of();
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                boolean superadmin = pattern.equals("/superadmin") || pattern.startsWith("/superadmin/")
                        || pattern.equals("/api/v1/superadmin") || pattern.startsWith("/api/v1/superadmin/");
                if (superadmin && !PUBLIC.contains(pattern)) {
                    for (RequestMethod method : declared.isEmpty() ? ALL_METHODS : declared) {
                        routes.add(new Route(pattern, method));
                    }
                }
            }
        }
        assertThat(routes).as("rutas del superadmin encontradas").hasSizeGreaterThanOrEqualTo(10);
        return routes;
    }

    private MockHttpServletResponse send(Route route, MediaType contentType, java.util.function.UnaryOperator<MockHttpServletRequestBuilder> auth) throws Exception {
        String uri = route.pattern().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(route.method().name()), uri);
        if (route.method() != RequestMethod.GET) {
            builder.contentType(contentType).content(contentType == MediaType.APPLICATION_JSON ? "{}" : "a=b");
        }
        return mockMvc.perform(auth.apply(builder)).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Route route, java.util.function.UnaryOperator<MockHttpServletRequestBuilder> auth) throws Exception {
        return send(route, MediaType.APPLICATION_JSON, auth);
    }

    @Test
    @DisplayName("Cada ruta de /superadmin/** y /api/v1/superadmin/**: sin sesión 401/login, organizador 403, superadmin pasa")
    void everySuperadminRouteRequiresTheRole() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Route route : superadminRoutes()) {
            var anonymous = send(route, b -> b);
            if (route.isApi()) {
                assertThat(anonymous.getStatus()).as("%s sin sesión", route).isEqualTo(401);
            } else {
                assertThat(anonymous.getStatus()).as("%s sin sesión", route).isEqualTo(302);
                assertThat(anonymous.getRedirectedUrl()).as("%s sin sesión", route).isEqualTo("/superadmin/login");
            }

            var organizerBearer = send(route, b -> b.header("Authorization", "Bearer " + organizerToken));
            assertThat(organizerBearer.getStatus()).as("%s con el JWT de un organizador", route).isEqualTo(403);

            var organizerCookie = send(route, b -> b.cookie(new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME, organizerToken)));
            assertThat(organizerCookie.getStatus()).as("%s con la cookie de un organizador", route).isEqualTo(anonymous.getStatus());

            var superadmin = send(route, b -> b.cookie(new Cookie(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, superadminToken)));
            assertThat(superadmin.getStatus()).as("%s con la sesión del superadmin", route).isNotIn(401, 403);
            checked.add(route.toString());
        }
        System.out.println("SUPERADMIN rutas verificadas " + checked.size() + ": " + checked);
    }

    @Test
    @DisplayName("Cada POST del superadmin sin Content-Type JSON (un formulario de otro sitio) -> 415, sin ejecutar nada")
    void everySuperadminPostRequiresJson() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Route route : superadminRoutes()) {
            if (route.method() != RequestMethod.POST) {
                continue;
            }
            var form = send(route, MediaType.APPLICATION_FORM_URLENCODED,
                    b -> b.cookie(new Cookie(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, superadminToken)));
            assertThat(form.getStatus()).as("%s con un formulario", route).isEqualTo(415);
            var plain = send(route, MediaType.TEXT_PLAIN,
                    b -> b.cookie(new Cookie(JwtAuthenticationFilter.SUPERADMIN_COOKIE_NAME, superadminToken)));
            assertThat(plain.getStatus()).as("%s con text/plain", route).isEqualTo(415);
            checked.add(route.toString());
        }
        assertThat(checked).as("POST del superadmin").hasSizeGreaterThanOrEqualTo(7);
        System.out.println("SUPERADMIN POST con 415 " + checked.size() + ": " + checked);
    }
}
