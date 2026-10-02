package com.tuapp.eventfoto.api;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.comment.CommentRepository;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.Photo;
import com.tuapp.eventfoto.photo.PhotoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
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
 * Fase 9.1 Bloque 4: el equivalente, para el lado invitado, del test de enumeración del panel.
 *
 * Recorre el mapping de Spring (no hay lista de rutas escrita a mano) y, para cada ruta pública
 * que recibe un id (variable de ruta terminada en "Id" o parámetro "key"), exige:
 *   (a) llevar {slug} -- y entonces, con el slug de A y el id de un recurso de B, responder 404 con
 *       el mismo cuerpo que un id inexistente (consulta acotada al evento); o
 *   (b) figurar en {@link #EXCEPTIONS} con su motivo.
 * Una variable de ruta sin fixture, o un tipo de body sin fixture, hace fallar el test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicRouteEnumerationTest {

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/events", "/api/v1/photos", "/api/v1/messages", "/api/v1/comments", "/api/v1/storage", "/e");

    /** Rutas públicas con un id/key que NO llevan {slug}. Patrones exactos, cada una con su motivo. */
    private record RouteException(String pattern, String reason) {
    }

    private static final List<RouteException> EXCEPTIONS = List.of(
            new RouteException("/api/v1/storage/local-upload",
                    "Solo existe con app.storage.mode=local (LocalStorageController es @ConditionalOnProperty: en producción la ruta no existe) "
                            + "y exige una clave events/{uuid}/{uuid}.ext. Lo prueba LocalStorageRestrictionTest."),
            new RouteException("/api/v1/storage/files",
                    "Solo existe con app.storage.mode=local y sirve únicamente claves con formato events/{uuid}/{uuid}.ext del directorio local. "
                            + "Lo prueba LocalStorageRestrictionTest."));

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");
    private static final Set<String> CHILD_VARIABLES = Set.of("photoId", "messageId", "commentId");
    private static final List<RequestMethod> ALL_METHODS =
            List.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** Body válido por tipo de @RequestBody: sin esto la validación (400) taparía el 404 que se quiere ver. */
    private static final Map<String, String> BODY_FIXTURES = Map.of(
            "CreateCommentRequestDTO", "{\"authorName\":\"Ana\",\"text\":\"Qué linda foto\",\"guestToken\":\"token-enum-aaaa\"}",
            "CreateMessageRequestDTO", "{\"authorName\":\"Ana\",\"text\":\"Felicidades\",\"guestToken\":\"token-enum-aaaa\"}");

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private MockMvc mockMvc;
    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private CommentRepository commentRepository;

    private Event eventA;
    private Event eventB;
    private Photo photoB;
    private Map<String, String> foreignFixtures;

    @BeforeEach
    void setUp() {
        cleanUp();
        Organizer organizerA = organizerRepository.save(Organizer.builder().email("pub-a@test.com").build());
        Organizer organizerB = organizerRepository.save(Organizer.builder().email("pub-b@test.com").build());
        eventA = event(organizerA, "evento-pub-a-k7m2xq9p");
        eventB = event(organizerB, "evento-pub-b-x4h8wt2n");
        photoB = photoRepository.save(Photo.builder().event(eventB).storageKey("events/" + eventB.getId() + "/" + UUID.randomUUID() + ".jpg").build());
        Message messageB = messageRepository.save(Message.builder().event(eventB).authorName("Beto").text("Hola B").build());
        Comment commentB = commentRepository.save(Comment.builder().photo(photoB).authorName("Beto").text("Linda B").build());

        foreignFixtures = new LinkedHashMap<>();
        foreignFixtures.put("slug", eventA.getSlug());           // la request es "desde A"...
        foreignFixtures.put("photoId", photoB.getId().toString()); // ...con los ids de recursos de B
        foreignFixtures.put("messageId", messageB.getId().toString());
        foreignFixtures.put("commentId", commentB.getId().toString());
    }

    @AfterEach
    void cleanUp() {
        commentRepository.deleteAll();
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private Event event(Organizer organizer, String slug) {
        return eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento " + slug).slug(slug)
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
    }

    private record PublicRoute(String pattern, RequestMethod method, RequestMappingInfo info, HandlerMethod handler) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }

        boolean takesAnId() {
            return variablesOf(pattern).stream().anyMatch(name -> name.endsWith("Id")) || hasRequestParamNamed("key");
        }

        boolean hasRequestParamNamed(String wanted) {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
                if (annotation == null) {
                    continue;
                }
                parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                String name = !annotation.name().isEmpty() ? annotation.name() : parameter.getParameterName();
                if (wanted.equals(name)) {
                    return true;
                }
            }
            return false;
        }

        String bodyType() {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    return parameter.getParameterType().getSimpleName();
                }
            }
            return null;
        }
    }

    private List<PublicRoute> publicRoutes() {
        List<PublicRoute> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null ? info.getPathPatternsCondition().getPatternValues() : Set.of();
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (PUBLIC_PREFIXES.stream().noneMatch(prefix -> pattern.equals(prefix) || pattern.startsWith(prefix + "/"))) {
                    continue;
                }
                for (RequestMethod method : declared.isEmpty() ? ALL_METHODS : declared) {
                    routes.add(new PublicRoute(pattern, method, info, entry.getValue()));
                }
            }
        }
        return routes;
    }

    private static List<String> variablesOf(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // ---------- (a)/(b): cobertura ----------

    @Test
    @DisplayName("Toda ruta pública que recibe un id lleva {slug} o está en la lista de excepciones con motivo")
    void everyPublicRouteWithAnIdHasSlugOrAnException() {
        List<String> offenders = new ArrayList<>();
        for (PublicRoute route : publicRoutes()) {
            if (route.takesAnId() && !route.pattern().contains("{slug}") && EXCEPTIONS.stream().noneMatch(e -> e.pattern().equals(route.pattern()))) {
                offenders.add(route + "  (" + route.handler().getShortLogMessage() + ")");
            }
        }
        assertThat(offenders)
                .as("Rutas PÚBLICAS que reciben un id/key SIN {slug} y fuera de las excepciones: no se puede verificar que el recurso "
                        + "pertenezca al evento. Qué hacer: colgalas de /api/v1/events/{slug}/... y buscá el recurso con una consulta acotada "
                        + "al evento (findByIdAndEventId) o, si no aplica, agregalas a EXCEPTIONS con su motivo y un test que lo pruebe.")
                .isEmpty();
    }

    @Test
    @DisplayName("Las excepciones son exactas (sin comodines ni {slug}) y cada una coincide con una ruta pública real con id/key")
    void exceptionsAreExactAndReal() {
        Set<String> realPatterns = new LinkedHashSet<>();
        publicRoutes().stream().filter(PublicRoute::takesAnId).forEach(route -> realPatterns.add(route.pattern()));

        for (RouteException exception : EXCEPTIONS) {
            assertThat(exception.pattern()).doesNotContain("*").doesNotContain("{");
            assertThat(exception.reason()).isNotBlank();
            assertThat(realPatterns).as("la excepción '%s' no coincide con ninguna ruta pública real (¿sobra?)", exception.pattern())
                    .contains(exception.pattern());
        }
    }

    // ---------- comportamiento: slug propio + id de otro evento ----------

    @Test
    @DisplayName("Con el slug de A y el id de un recurso de B, cada ruta pública con id responde 404 idéntico al de un id inexistente")
    void foreignIdIsIndistinguishableFromMissingOnEveryRoute() throws Exception {
        List<String> checked = new ArrayList<>();
        for (PublicRoute route : publicRoutes()) {
            if (!route.pattern().contains("{slug}") || variablesOf(route.pattern()).stream().noneMatch(CHILD_VARIABLES::contains)) {
                continue;
            }
            Map<String, String> missing = new LinkedHashMap<>(foreignFixtures);
            CHILD_VARIABLES.forEach(name -> missing.put(name, UUID.randomUUID().toString()));

            MockHttpServletResponse foreign = send(route, foreignFixtures);
            MockHttpServletResponse control = send(route, missing);

            assertThat(foreign.getStatus()).as("%s con slug de A e id de B", route).isEqualTo(404);
            assertThat(control.getStatus()).as("%s con slug de A e id inexistente", route).isEqualTo(404);
            assertThat(normalize(foreign.getContentAsString()))
                    .as("%s: un id de otro evento debe responder igual que un id inexistente", route)
                    .isEqualTo(normalize(control.getContentAsString()));
            checked.add(route.toString());
        }
        assertThat(checked).as("rutas públicas con id verificadas").isNotEmpty();
        System.out.println("PUBLIC-ENUMERATION verificó " + checked.size() + " rutas/método: " + checked);

        // Nada de B se modificó y no se creó nada en A.
        assertThat(commentRepository.findAll()).hasSize(1);
        assertThat(photoRepository.count()).isEqualTo(1);
    }

    private MockHttpServletResponse send(PublicRoute route, Map<String, String> fixtures) throws Exception {
        rateLimiterService.resetRateLimits(); // los POST de comentarios tienen rate limit por IP/token
        Matcher matcher = PATH_VARIABLE.matcher(route.pattern());
        StringBuilder uri = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = fixtures.get(name);
            if (value == null) {
                fail("La ruta pública %s tiene la variable {%s} y el test no tiene fixture para ella. "
                        + "Agregala a foreignFixtures en PublicRouteEnumerationTest (y, si es un id hijo, a CHILD_VARIABLES).", route, name);
            }
            matcher.appendReplacement(uri, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(uri);

        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(route.method().name()), uri.toString());
        Set<MediaType> produces = route.info().getProducesCondition().getProducibleMediaTypes();
        builder.accept(produces.isEmpty() ? new MediaType[]{MediaType.ALL} : produces.toArray(new MediaType[0]));

        String bodyType = route.bodyType();
        if (bodyType != null) {
            String body = BODY_FIXTURES.get(bodyType);
            if (body == null) {
                fail("La ruta pública %s recibe un @RequestBody de tipo %s y el test no tiene un body válido para él. Agregalo a BODY_FIXTURES.", route, bodyType);
            }
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder).andReturn().getResponse();
    }

    private String normalize(String body) {
        return body
                .replaceAll("\"timestamp\":\"[^\"]*\",?", "")
                .replace(eventA.getSlug(), "SLUG")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID");
    }
}
