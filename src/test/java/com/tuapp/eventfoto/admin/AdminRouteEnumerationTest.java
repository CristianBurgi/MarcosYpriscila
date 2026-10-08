package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.AdminRouteExceptions;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.WizardRouteExceptions;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Fase 9.1 Bloque 3: el test que impide que un endpoint del panel quede sin protección.
 *
 * Recorre TODAS las rutas que el mapping de Spring tiene bajo /admin y /api/v1/admin (no hay
 * lista escrita a mano) y exige que cada una:
 *   - lleve {slug} (queda cubierta por EventAccessInterceptor), o
 *   - figure en AdminRouteExceptions, con su motivo.
 * Después arma una request por ruta y método, sustituyendo cada variable de ruta por un
 * fixture según su nombre, y comprueba con el JWT del organizador A:
 *   1. contra el evento de B: 404 idéntico al de un slug inexistente;
 *   2. con slug propio + id hijo de B: 404 idéntico al de un id hijo inexistente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminRouteEnumerationTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");
    private static final Set<String> CHILD_VARIABLES = Set.of("photoId", "messageId", "commentId");
    private static final List<RequestMethod> ALL_METHODS =
            List.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Cookie cookieOfA;
    private Organizer organizerA;
    private Event eventA;
    private Event eventB;
    private Map<String, String> foreignFixtures;

    @BeforeEach
    void setUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();

        Organizer organizerA = organizerRepository.save(Organizer.builder().email("enum-a@test.com").build());
        Organizer organizerB = organizerRepository.save(Organizer.builder().email("enum-b@test.com").build());
        this.organizerA = organizerA;
        eventA = event(organizerA, "evento-a-k7m2xq9p", true);
        // B con el wizard SIN completar a propósito: A contra un evento ajeno en ese estado sigue siendo 404,
        // nunca el redirect al wizard ni el 409 (pasada 1).
        eventB = event(organizerB, "evento-b-x4h8wt2n", false);

        Photo photoB = photoRepository.save(Photo.builder().event(eventB).storageKey("photos/b/1.jpg").build());
        Message messageB = messageRepository.save(Message.builder().event(eventB).authorName("Beto").text("Hola B").build());
        Comment commentB = commentRepository.save(Comment.builder().photo(photoB).authorName("Beto").text("Linda B").build());

        foreignFixtures = new LinkedHashMap<>();
        foreignFixtures.put("slug", eventB.getSlug());
        foreignFixtures.put("photoId", photoB.getId().toString());
        foreignFixtures.put("messageId", messageB.getId().toString());
        foreignFixtures.put("commentId", commentB.getId().toString());
        foreignFixtures.put("item", "tarjetas"); // casilla de la checklist (9.8): una clave válida

        cookieOfA = new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizerA.getId(), organizerA.getEmail(), organizerA.getTokenVersion()));
    }

    private Event event(Organizer organizer, String slug, boolean wizardCompleted) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now().plusDays(3))
                .isActive(true).origin(EventOrigin.PAID).wizardCompletedAt(wizardCompleted ? Instant.now() : null).build());
    }

    /** Una ruta bajo el panel, con uno de los métodos HTTP que declara. */
    private record PanelRoute(String pattern, RequestMethod method, RequestMappingInfo info, HandlerMethod handler) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private List<PanelRoute> panelRoutes() {
        List<PanelRoute> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues() : Set.of();
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!isPanelPath(pattern)) {
                    continue;
                }
                for (RequestMethod method : declared.isEmpty() ? ALL_METHODS : declared) {
                    routes.add(new PanelRoute(pattern, method, info, entry.getValue()));
                }
            }
        }
        return routes;
    }

    private static boolean isPanelPath(String pattern) {
        return pattern.equals("/admin") || pattern.startsWith("/admin/")
                || pattern.equals("/api/v1/admin") || pattern.startsWith("/api/v1/admin/");
    }

    // ---------- 0. El lado del moderador: todo bajo /moderar y /api/v1/moderate lleva {token} ----------

    @Test
    @DisplayName("Cada ruta de /moderar/** y /api/v1/moderate/** lleva {token}: la resuelve EventAccessInterceptor (ámbito MODERATOR) o el build rompe")
    void everyModeratorRouteCarriesTheToken() {
        List<String> all = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = entry.getKey().getPathPatternsCondition() != null
                    ? entry.getKey().getPathPatternsCondition().getPatternValues() : Set.of();
            for (String pattern : patterns) {
                if (pattern.equals("/moderar") || pattern.startsWith("/moderar/")
                        || pattern.equals("/api/v1/moderate") || pattern.startsWith("/api/v1/moderate/")) {
                    all.add(pattern);
                    if (!pattern.contains("{token}")) {
                        offenders.add(pattern + "  (" + entry.getValue().getShortLogMessage() + ")");
                    }
                }
            }
        }
        assertThat(all).as("rutas del moderador encontradas").isNotEmpty();
        assertThat(offenders)
                .as("Rutas del moderador SIN {token}: el interceptor las cerraría con 404 (no pueden resolver el evento). "
                        + "Qué hacer: agregales {token} a la ruta.")
                .isEmpty();
    }

    // ---------- 1. Cobertura: {slug} o excepción con motivo ----------

    @Test
    @DisplayName("Cada ruta de /admin/** y /api/v1/admin/** lleva {slug} o está en AdminRouteExceptions")
    void everyPanelRouteIsCoveredOrExplicitlyExcepted() {
        List<String> unprotected = new ArrayList<>();
        for (PanelRoute route : panelRoutes()) {
            boolean excepted = AdminRouteExceptions.isException(route.pattern());
            boolean hasSlug = route.pattern().contains("{slug}");
            if (!excepted && !hasSlug) {
                unprotected.add(route.toString() + "  (" + route.handler().getShortLogMessage() + ")");
            }
        }
        assertThat(unprotected)
                .as("Rutas del panel SIN {slug} y fuera de AdminRouteExceptions: quedarían sin protección de evento. "
                        + "Qué hacer: agregale {slug} a la ruta (queda protegida por EventAccessInterceptor) o, si de verdad "
                        + "no opera sobre un evento, declarala en AdminRouteExceptions con su motivo.")
                .isEmpty();
    }

    @Test
    @DisplayName("Las excepciones son exactas: cada una coincide con una ruta real y ninguna contiene {slug} ni comodines")
    void exceptionsAreExactAndReal() {
        Set<String> realPatterns = new LinkedHashSet<>();
        panelRoutes().forEach(route -> realPatterns.add(route.pattern()));

        for (AdminRouteExceptions.Entry entry : AdminRouteExceptions.ENTRIES) {
            assertThat(entry.pattern()).as("excepción con {slug}: %s", entry.pattern()).doesNotContain("{slug}");
            assertThat(entry.pattern()).as("excepción con comodín: %s", entry.pattern()).doesNotContain("*").doesNotContain("{");
            assertThat(entry.reason()).as("excepción sin motivo: %s", entry.pattern()).isNotBlank();
            assertThat(realPatterns)
                    .as("la excepción '%s' no coincide con ninguna ruta real del mapping (¿sobra?)", entry.pattern())
                    .contains(entry.pattern());
        }
    }

    // ---------- 2. Comportamiento: A contra B, en cada ruta y método ----------

    @Test
    @DisplayName("A contra el evento de B -> 404 idéntico al de un slug inexistente, en cada ruta y método con {slug}")
    void foreignEventIsIndistinguishableFromMissingOnEveryRoute() throws Exception {
        List<String> checked = new ArrayList<>();
        for (PanelRoute route : panelRoutes()) {
            if (AdminRouteExceptions.isException(route.pattern())) {
                continue;
            }
            Map<String, String> missing = new LinkedHashMap<>(foreignFixtures);
            missing.put("slug", "no-existe-zzzzzzzz");

            var foreign = send(route, foreignFixtures);
            var control = send(route, missing);

            assertThat(foreign.getStatus()).as("%s con el evento de B", route).isEqualTo(404);
            assertThat(control.getStatus()).as("%s con slug inexistente", route).isEqualTo(404);
            assertThat(normalize(foreign.getContentAsString()))
                    .as("%s: el cuerpo del 404 de 'ajeno' debe ser igual al de 'no existe'", route)
                    .isEqualTo(normalize(control.getContentAsString()));
            checked.add(route.toString());
        }
        assertThat(checked).as("rutas verificadas").isNotEmpty();
        System.out.println("ENUMERATION pasada 1 (evento ajeno), " + checked.size() + " rutas/métodos: " + checked);
    }

    @Test
    @DisplayName("Slug propio + id hijo de B -> 404 idéntico al de un id inexistente, en cada ruta con id")
    void foreignChildIdIsIndistinguishableFromMissingOnEveryRoute() throws Exception {
        List<String> checked = new ArrayList<>();
        for (PanelRoute route : panelRoutes()) {
            if (AdminRouteExceptions.isException(route.pattern()) || variablesOf(route.pattern()).stream().noneMatch(CHILD_VARIABLES::contains)) {
                continue;
            }
            Map<String, String> ownSlugForeignChild = new LinkedHashMap<>(foreignFixtures);
            ownSlugForeignChild.put("slug", eventA.getSlug());
            Map<String, String> ownSlugMissingChild = new LinkedHashMap<>(ownSlugForeignChild);
            CHILD_VARIABLES.forEach(name -> ownSlugMissingChild.put(name, UUID.randomUUID().toString()));

            var foreignChild = send(route, ownSlugForeignChild);
            var control = send(route, ownSlugMissingChild);

            assertThat(foreignChild.getStatus()).as("%s con slug propio e id de B", route).isEqualTo(404);
            assertThat(control.getStatus()).as("%s con slug propio e id inexistente", route).isEqualTo(404);
            assertThat(normalize(foreignChild.getContentAsString()))
                    .as("%s: id de otro evento debe responder igual que id inexistente", route)
                    .isEqualTo(normalize(control.getContentAsString()));
            checked.add(route.toString());
        }
        assertThat(checked).as("rutas con id hijo verificadas").isNotEmpty();
        System.out.println("ENUMERATION pasada 2 (slug propio + id ajeno), " + checked.size() + " rutas/métodos: " + checked);

        // Nada del evento de B se modificó durante todo el recorrido.
        assertThat(photoRepository.count()).isEqualTo(1);
        assertThat(messageRepository.count()).isEqualTo(1);
        assertThat(commentRepository.count()).isEqualTo(1);
        assertThat(eventRepository.findById(eventB.getId()).orElseThrow().isActive()).isTrue();
    }

    // ---------- 3. Wizard sin completar (Fase 9.5) ----------

    @Test
    @DisplayName("Wizard sin completar: cada vista del panel redirige al wizard y cada API responde 409 WIZARD_REQUIRED, salvo WizardRouteExceptions")
    void everyPanelRouteIsBlockedUntilTheWizardIsDone() throws Exception {
        Event pending = event(organizerA, "evento-pendiente-m3q8zt5r", false);
        String wizardUrl = "/admin/eventos/" + pending.getSlug() + "/wizard";
        List<String> blocked = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (PanelRoute route : panelRoutes()) {
            if (AdminRouteExceptions.isException(route.pattern())) {
                continue;
            }
            Map<String, String> fixtures = new LinkedHashMap<>(foreignFixtures);
            fixtures.put("slug", pending.getSlug());
            CHILD_VARIABLES.forEach(name -> fixtures.put(name, UUID.randomUUID().toString()));
            // Cada ruta arranca con el wizard sin completar (POST .../wizard/complete lo completa de verdad).
            pending.setWizardCompletedAt(null);
            pending = eventRepository.saveAndFlush(pending);
            var response = send(route, fixtures);
            boolean redirectedToWizard = response.getStatus() == 302 && wizardUrl.equals(response.getRedirectedUrl());
            boolean wizardRequired = response.getStatus() == 409 && response.getContentAsString().contains("\"WIZARD_REQUIRED\"");

            if (WizardRouteExceptions.isException(route.pattern())) {
                assertThat(redirectedToWizard || wizardRequired).as("%s está en WizardRouteExceptions y quedó bloqueada", route).isFalse();
                open.add(route.toString());
            } else if (route.pattern().startsWith("/admin/")) {
                assertThat(redirectedToWizard).as("%s (vista) debe redirigir a %s; respondió %d", route, wizardUrl, response.getStatus()).isTrue();
                blocked.add(route.toString());
            } else {
                assertThat(wizardRequired).as("%s (API) debe responder 409 WIZARD_REQUIRED; respondió %d %s",
                        route, response.getStatus(), response.getContentAsString()).isTrue();
                blocked.add(route.toString());
            }
        }
        assertThat(blocked).as("rutas bloqueadas").isNotEmpty();
        assertThat(open).as("rutas del wizard").hasSize(WizardRouteExceptions.ENTRIES.stream()
                .mapToInt(entry -> (int) panelRoutes().stream().filter(r -> r.pattern().equals(entry.pattern())).count()).sum());
        System.out.println("WIZARD bloqueadas " + blocked.size() + ": " + blocked + " | abiertas " + open.size() + ": " + open);
    }

    @Test
    @DisplayName("Las excepciones del wizard son exactas: cada una coincide con una ruta real, lleva {slug} y tiene motivo")
    void wizardExceptionsAreExactAndReal() {
        Set<String> realPatterns = new LinkedHashSet<>();
        panelRoutes().forEach(route -> realPatterns.add(route.pattern()));
        for (WizardRouteExceptions.Entry entry : WizardRouteExceptions.ENTRIES) {
            assertThat(entry.pattern()).as("excepción del wizard sin {slug}: %s", entry.pattern()).contains("{slug}");
            assertThat(entry.pattern().replace("{slug}", "")).as("excepción con comodín: %s", entry.pattern())
                    .doesNotContain("*").doesNotContain("{");
            assertThat(entry.reason()).as("excepción sin motivo: %s", entry.pattern()).isNotBlank();
            assertThat(realPatterns).as("la excepción del wizard '%s' no coincide con ninguna ruta real (¿sobra?)", entry.pattern())
                    .contains(entry.pattern());
        }
    }

    // ---------- armado de requests desde el mapping ----------

    private static List<String> variablesOf(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private org.springframework.mock.web.MockHttpServletResponse send(PanelRoute route, Map<String, String> fixtures) throws Exception {
        Matcher matcher = PATH_VARIABLE.matcher(route.pattern());
        StringBuilder uri = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = fixtures.get(name);
            if (value == null) {
                fail("La ruta %s tiene la variable {%s} y el test no tiene fixture para ella. "
                        + "Agregala a foreignFixtures en AdminRouteEnumerationTest (y, si es un id hijo, a CHILD_VARIABLES).", route, name);
            }
            matcher.appendReplacement(uri, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(uri);

        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(route.method().name()), uri.toString()).cookie(cookieOfA);
        Set<MediaType> produces = route.info().getProducesCondition().getProducibleMediaTypes();
        if (!produces.isEmpty()) {
            builder.accept(produces.toArray(new MediaType[0]));
        } else {
            builder.accept(MediaType.ALL);
        }
        if (!route.info().getConsumesCondition().isEmpty()) {
            builder.contentType(route.info().getConsumesCondition().getConsumableMediaTypes().iterator().next());
            builder.content("{}");
        }
        return mockMvc.perform(builder).andReturn().getResponse();
    }

    /** Quita lo que legítimamente varía entre dos respuestas equivalentes: timestamp, slugs, ids, path. */
    private String normalize(String body) {
        return body
                .replaceAll("\"timestamp\":\"[^\"]*\",?", "")
                .replace(eventA.getSlug(), "SLUG").replace(eventB.getSlug(), "SLUG").replace("no-existe-zzzzzzzz", "SLUG")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID");
    }
}
