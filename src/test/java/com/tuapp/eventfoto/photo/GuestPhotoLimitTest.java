package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.GuestQuotaExceededException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventCreationService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.organizer.OrganizerTokenRepository;
import com.tuapp.eventfoto.superadmin.SuperadminEventService;
import com.tuapp.eventfoto.testsupport.TestEvents;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.5: el límite de fotos por invitado es por evento (24 o sin límite) y se puede cambiar en pleno evento.
 * NO es @Transactional a propósito: cada llamada al servicio abre y cierra su propia transacción, como en
 * producción (un test transaccional enmascara carreras y lecturas de límites viejos).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuestPhotoLimitTest {

    private static final String TOKEN = "token-limite-fotos";

    @Autowired private GuestQuotaService guestQuotaService;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private OrganizerTokenRepository organizerTokenRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private EventCreationService eventCreationService;
    @Autowired private SuperadminEventService superadminEventService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MockMvc mockMvc;

    private Organizer organizer;

    @BeforeEach
    void setUp() {
        cleanUp();
        organizer = organizerRepository.save(Organizer.builder().email("limite@test.com").build());
    }

    @AfterEach
    void cleanUp() {
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerTokenRepository.deleteAll(); // el flujo superadmin deja un token de activación
        organizerRepository.deleteAll();
    }

    private int upload(Event event, String token, int times) {
        int accepted = 0;
        for (int i = 0; i < times; i++) {
            try {
                guestQuotaService.incrementUsageOrThrow(event, token);
                accepted++;
            } catch (GuestQuotaExceededException e) {
                // rechazada
            }
        }
        return accepted;
    }

    private int used(Event event, String token) {
        return guestQuotaRepository.findByEventIdAndGuestToken(event.getId(), token).map(GuestQuota::getPhotosUploaded).orElse(0);
    }

    private Cookie cookieOf(Organizer o) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(o.getId(), o.getEmail(), o.getTokenVersion()));
    }

    // ---------- 24 y sin límite ----------

    @Test
    @DisplayName("Modo 24: la foto 24 se acepta y la 25 se rechaza con el mensaje con el número del evento")
    void photo24AcceptedAnd25Rejected() {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-24-k7m2xq9p", 24);

        assertThat(upload(event, TOKEN, 24)).isEqualTo(24);
        assertThat(used(event, TOKEN)).isEqualTo(24);

        assertThatThrownBy(() -> guestQuotaService.incrementUsageOrThrow(event, TOKEN))
                .isInstanceOf(GuestQuotaExceededException.class)
                .hasMessageContaining("Ya usaste tus 24 fotos");
        assertThat(used(event, TOKEN)).isEqualTo(24);
    }

    @Test
    @DisplayName("Modo sin límite: se aceptan más de 24 fotos y el contador sigue contando")
    void moreThan24AcceptedWhenUnlimited() {
        Event event = TestEvents.unlimited(eventRepository, organizer, "evento-sin-limite-x4h8wt2n");

        assertThat(upload(event, TOKEN, 40)).isEqualTo(40);
        assertThat(used(event, TOKEN)).isEqualTo(40);
        assertThat(guestQuotaService.getRemainingPhotos(event, TOKEN)).isNull();
    }

    // ---------- Cambio de modo en pleno evento ----------

    @Test
    @DisplayName("De 24 a sin límite en pleno evento: el invitado que ya había agotado su cupo puede seguir subiendo")
    void switchingToUnlimitedEnablesExhaustedGuest() {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-cambio-a-k7m2xq9p", 24);
        assertThat(upload(event, TOKEN, 30)).isEqualTo(24);

        guestQuotaService.updateGuestPhotoLimit(event.getId(), true);
        Event reloaded = eventRepository.findById(event.getId()).orElseThrow();

        assertThat(upload(reloaded, TOKEN, 6)).isEqualTo(6);
        assertThat(used(event, TOKEN)).isEqualTo(30);
    }

    @Test
    @DisplayName("De sin límite a 24 con un invitado que ya subió más de 24: queda bloqueado, sin errores ni contadores negativos")
    void switchingBackTo24BlocksGuestWhoUploadedMore() {
        Event event = TestEvents.unlimited(eventRepository, organizer, "evento-vuelta-b-x4h8wt2n");
        assertThat(upload(event, TOKEN, 30)).isEqualTo(30);

        var state = guestQuotaService.updateGuestPhotoLimit(event.getId(), false);
        assertThat(state.unlimited()).isFalse();
        assertThat(state.maxPhotosPerGuest()).isEqualTo(24);
        Event reloaded = eventRepository.findById(event.getId()).orElseThrow();

        assertThat(upload(reloaded, TOKEN, 3)).isZero();
        assertThat(used(event, TOKEN)).as("el contador no se mueve ni baja").isEqualTo(30);
        assertThat(guestQuotaService.getRemainingPhotos(reloaded, TOKEN)).as("nunca negativo").isZero();
        var quota = guestQuotaService.getQuota(reloaded, TOKEN);
        assertThat(quota.unlimited()).isFalse();
        assertThat(quota.remainingPhotos()).isZero();

        // y otro invitado del mismo evento, que no subió nada, tiene su cupo completo
        assertThat(upload(reloaded, "otro-invitado", 30)).isEqualTo(24);
    }

    @Test
    @DisplayName("El límite que se aplica es el de la base, no el de una copia vieja del Event (open-in-view: false)")
    void incrementUsesLimitFromDatabaseNotFromStaleEntity() {
        Event stale = TestEvents.limited(eventRepository, organizer, "evento-viejo-m3n5pq7r", 24);
        assertThat(upload(stale, TOKEN, 24)).isEqualTo(24);

        // el organizador pasa a "sin límite" DESPUÉS de que la subida cargó su copia del evento (límite 24)
        guestQuotaService.updateGuestPhotoLimit(stale.getId(), true);
        assertThat(stale.getMaxPhotosPerGuest()).as("la copia sigue diciendo 24").isEqualTo(24);
        assertThat(upload(stale, TOKEN, 1)).as("se acepta: manda la base").isEqualTo(1);

        // y al revés: copia "sin límite", base en 24 con el invitado ya pasado
        guestQuotaService.updateGuestPhotoLimit(stale.getId(), false);
        Event staleUnlimited = Event.builder().id(stale.getId()).maxPhotosPerGuest(null).build();
        assertThat(upload(staleUnlimited, TOKEN, 1)).as("se rechaza: manda la base").isZero();
    }

    // ---------- Concurrencia ----------

    @Test
    @DisplayName("N subidas simultáneas del mismo invitado con límite 24 nunca superan 24")
    void concurrentUploadsNeverExceedTheLimit() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-carrera-k7m2xq9p", 24);
        int threads = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    guestQuotaService.incrementUsageOrThrow(event, TOKEN);
                    accepted.incrementAndGet();
                } catch (GuestQuotaExceededException e) {
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(unexpected).as("ninguna excepción inesperada").isEmpty();
        assertThat(accepted.get() + rejected.get()).isEqualTo(threads);
        assertThat(accepted.get()).as("nunca más de 24 aceptadas").isLessThanOrEqualTo(24);
        assertThat(used(event, TOKEN)).as("el contador en la base tampoco pasa de 24").isEqualTo(accepted.get()).isLessThanOrEqualTo(24);
        assertThat(accepted.get()).as("y se llena hasta 24 (60 intentos)").isEqualTo(24);
    }

    // ---------- Aislamiento entre eventos con el mismo guestToken ----------

    @Test
    @DisplayName("Mismo guestToken: A sin límite y B en 24 -> lo subido en A no toca el cupo de B, y B sigue limitado a 24")
    void unlimitedEventDoesNotAffectLimitedEvent() {
        Event a = TestEvents.unlimited(eventRepository, organizer, "evento-a-ilimitado-k7m2xq9p");
        Event b = TestEvents.limited(eventRepository, organizer, "evento-b-limitado-x4h8wt2n", 24);

        assertThat(upload(b, TOKEN, 1)).isEqualTo(1); // crea la fila de B
        assertThat(upload(a, TOKEN, 30)).isEqualTo(30);

        assertThat(used(b, TOKEN)).as("lo de A no se sumó a B").isEqualTo(1);
        assertThat(upload(b, TOKEN, 30)).as("B se llena hasta 24 en total").isEqualTo(23);
        assertThat(used(a, TOKEN)).isEqualTo(30);
    }

    @Test
    @DisplayName("Mismo guestToken: A en 24 y B sin límite -> agotar A no bloquea a B, y B no se limita")
    void limitedEventDoesNotAffectUnlimitedEvent() {
        Event a = TestEvents.limited(eventRepository, organizer, "evento-a-limitado-m3n5pq7r", 24);
        Event b = TestEvents.unlimited(eventRepository, organizer, "evento-b-ilimitado-t6v8yz2c");

        assertThat(upload(b, TOKEN, 1)).isEqualTo(1);
        assertThat(upload(a, TOKEN, 30)).isEqualTo(24);

        assertThat(used(b, TOKEN)).isEqualTo(1);
        assertThat(upload(b, TOKEN, 30)).as("B sigue sin límite").isEqualTo(30);
        assertThat(used(a, TOKEN)).isEqualTo(24);
    }

    // ---------- GET guest-quota ----------

    @Test
    @DisplayName("GET guest-quota con límite: unlimited=false y los dos números")
    void guestQuotaEndpointWithLimit() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-get-limite-k7m2xq9p", 24);
        upload(event, TOKEN, 5);

        mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited", is(false)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", is(24)))
                .andExpect(jsonPath("$.remainingPhotos", is(19)));
    }

    @Test
    @DisplayName("GET guest-quota sin límite: unlimited=true y los dos números en null (sin números mágicos)")
    void guestQuotaEndpointUnlimitedHasNoMagicNumbers() throws Exception {
        Event event = TestEvents.unlimited(eventRepository, organizer, "evento-get-ilimitado-x4h8wt2n");
        upload(event, TOKEN, 30);

        mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/guest-quota").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited", is(true)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", nullValue()))
                .andExpect(jsonPath("$.remainingPhotos", nullValue()));
    }

    // ---------- Panel del organizador ----------

    @Test
    @DisplayName("El organizador dueño cambia el límite en pleno evento y la respuesta trae el estado nuevo completo")
    void ownerCanChangeTheLimit() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-panel-k7m2xq9p", 24);
        String url = "/api/v1/admin/events/" + event.getSlug() + "/guest-photo-limit";

        mockMvc.perform(put(url).cookie(cookieOf(organizer)).contentType(MediaType.APPLICATION_JSON).content("{\"unlimited\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited", is(true)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", nullValue()));
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getMaxPhotosPerGuest()).isNull();

        mockMvc.perform(put(url).cookie(cookieOf(organizer)).contentType(MediaType.APPLICATION_JSON).content("{\"unlimited\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited", is(false)))
                .andExpect(jsonPath("$.maxPhotosPerGuest", is(24)));
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getMaxPhotosPerGuest()).isEqualTo(24);
    }

    @Test
    @DisplayName("El dashboard carga con el estado inicial correcto del selector (límite -> 'limited' marcado; sin límite -> 'unlimited' marcado)")
    void dashboardShowsCurrentLimitAsInitialState() throws Exception {
        Event limited = TestEvents.limited(eventRepository, organizer, "evento-dash-limite-k7m2xq9p", 24);
        Event unlimited = TestEvents.unlimited(eventRepository, organizer, "evento-dash-ilimitado-x4h8wt2n");

        String htmlLimited = mockMvc.perform(get("/admin/eventos/" + limited.getSlug()).cookie(cookieOf(organizer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(htmlLimited).containsPattern("id=\"guestLimitLimited\"[^>]*checked");
        assertThat(htmlLimited).doesNotContainPattern("id=\"guestLimitUnlimited\"[^>]*checked");
        assertThat(htmlLimited).contains("Hasta 24 fotos por invitado");

        String htmlUnlimited = mockMvc.perform(get("/admin/eventos/" + unlimited.getSlug()).cookie(cookieOf(organizer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(htmlUnlimited).containsPattern("id=\"guestLimitUnlimited\"[^>]*checked");
        assertThat(htmlUnlimited).doesNotContainPattern("id=\"guestLimitLimited\"[^>]*checked");
    }

    @Test
    @DisplayName("Body sin 'unlimited' -> 400 y el límite no cambia")
    void missingFieldIsRejected() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-panel-400-x4h8wt2n", 24);

        mockMvc.perform(put("/api/v1/admin/events/" + event.getSlug() + "/guest-photo-limit").cookie(cookieOf(organizer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getMaxPhotosPerGuest()).isEqualTo(24);
    }

    @Test
    @DisplayName("Organizador ajeno cambiando el límite -> 404 (igual que un slug inexistente) y el límite no cambia")
    void foreignOrganizerGets404AndLimitIsUntouched() throws Exception {
        Event eventOfOwner = TestEvents.limited(eventRepository, organizer, "evento-ajeno-k7m2xq9p", 24);
        Organizer intruder = organizerRepository.save(Organizer.builder().email("intruso@test.com").build());

        mockMvc.perform(put("/api/v1/admin/events/" + eventOfOwner.getSlug() + "/guest-photo-limit")
                        .cookie(cookieOf(intruder)).contentType(MediaType.APPLICATION_JSON).content("{\"unlimited\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/admin/events/no-existe-t6v8yz2c/guest-photo-limit")
                        .cookie(cookieOf(intruder)).contentType(MediaType.APPLICATION_JSON).content("{\"unlimited\":true}"))
                .andExpect(status().isNotFound());

        assertThat(eventRepository.findById(eventOfOwner.getId()).orElseThrow().getMaxPhotosPerGuest())
                .as("el límite del dueño no cambió").isEqualTo(24);
    }

    @Test
    @DisplayName("Sin sesión -> no pasa y el límite no cambia")
    void anonymousCannotChangeTheLimit() throws Exception {
        Event event = TestEvents.limited(eventRepository, organizer, "evento-anonimo-x4h8wt2n", 24);

        mockMvc.perform(put("/api/v1/admin/events/" + event.getSlug() + "/guest-photo-limit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unlimited\":true}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403, 404));
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getMaxPhotosPerGuest()).isEqualTo(24);
    }

    // ---------- Eventos nuevos ----------

    @Test
    @DisplayName("Los eventos nuevos nacen en 24 (EventCreationService, y el flujo superadmin de evento sin costo)")
    void newEventsAreBornWith24() {
        Event paid = eventCreationService.createEvent(organizer, "Boda de prueba", EventOrigin.PAID, null);
        assertThat(eventRepository.findById(paid.getId()).orElseThrow().getMaxPhotosPerGuest()).isEqualTo(24);

        var free = superadminEventService.createFreeEvent("gratis@test.com", "Cumple sin costo", "Familiar");
        assertThat(eventRepository.findById(free.event().getId()).orElseThrow().getMaxPhotosPerGuest()).isEqualTo(24);
    }
}
