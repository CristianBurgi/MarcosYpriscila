package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.JwtAuthenticationFilter;
import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.common.exception.InvalidFileContentException;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.photo.PhotoRepository;
import com.tuapp.eventfoto.storage.ImageContent;
import com.tuapp.eventfoto.storage.StorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fase 9.5: fecha, color, imagen de fondo y cierre del wizard, con la JVM en UTC (como Railway) y el reloj fijo
 * en los bordes de la hora de Argentina. También: aislamiento entre organizadores y las páginas de invitado
 * con y sin personalización.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventSettingsTest {

    /** Reloj que el test mueve a mano. */
    static class MutableClock extends Clock {
        Instant now = Instant.now();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private StorageService storageService;

    private Event event;
    private Cookie owner;
    private Cookie stranger;

    @BeforeAll
    static void jvmIsInUtc() {
        // La corrida entera va con -Duser.timezone=UTC (pom): cambiar la zona a mitad de la corrida desplaza las
        // fechas de H2. Acá solo se confirma que los bordes de abajo se prueban como en Railway.
        assertThat(TimeZone.getDefault().getRawOffset()).as("la JVM de los tests corre en UTC").isZero();
    }

    @BeforeEach
    void setUp() {
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        clock.now = Instant.parse("2026-10-06T12:00:00Z");
        when(storageService.generatePublicUrl(anyString())).thenAnswer(inv -> "/uploads/" + inv.getArgument(0));

        Organizer a = organizerRepository.save(Organizer.builder().email("settings-a@test.com").build());
        Organizer b = organizerRepository.save(Organizer.builder().email("settings-b@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(a).name("Cumple de Sofía").slug("cumple-sofia-k7m2xq9p")
                .uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
        owner = cookieOf(a);
        stranger = cookieOf(b);
    }

    private Cookie cookieOf(Organizer organizer) {
        return new Cookie(JwtAuthenticationFilter.ORGANIZER_COOKIE_NAME,
                jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion()));
    }

    private String api(String path) {
        return "/api/v1/admin/events/" + event.getSlug() + path;
    }

    private ResultActions putDate(String date) throws Exception {
        return mockMvc.perform(put(api("/date")).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\"}"));
    }

    private Event reload() {
        return eventRepository.findById(event.getId()).orElseThrow();
    }

    private void completeWizard(LocalDate date) {
        Event e = reload();
        e.setEventDate(date);
        e.setWizardCompletedAt(Instant.parse("2026-10-01T00:00:00Z"));
        eventRepository.save(e);
    }

    // ---------- fecha ----------

    @Test
    @DisplayName("JVM en UTC, 23:30 en Argentina: 'hoy' es el día argentino (se acepta) y ayer se rechaza")
    void todayIsTheArgentineDay() throws Exception {
        clock.now = Instant.parse("2026-10-06T02:30:00Z"); // 05/10 23:30 en Argentina; en UTC ya es 06/10
        putDate("2026-10-05").andExpect(status().isOk()).andExpect(jsonPath("$.eventDate").value("2026-10-05"));
        putDate("2026-10-04").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha del evento no puede ser anterior a hoy."));
    }

    @Test
    @DisplayName("Fecha inválida o a más de 2 años: 400")
    void invalidOrTooFarDates() throws Exception {
        putDate("2026-02-30").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("La fecha no es válida."));
        putDate("no-es-fecha").andExpect(status().isBadRequest());
        putDate("2028-10-06").andExpect(status().isOk());
        putDate("2028-10-07").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha del evento no puede ser más de 2 años adelante."));
    }

    @Test
    @DisplayName("Wizard completo: la fecha se edita hasta las 23:59 de dos días antes; desde las 00:00 del día anterior, no (hora de Argentina)")
    void dateLocksWhenTheUploadWindowOpens() throws Exception {
        completeWizard(LocalDate.parse("2026-12-10"));

        clock.now = Instant.parse("2026-12-09T02:59:00Z"); // 08/12 23:59 en Argentina
        putDate("2026-12-10").andExpect(status().isOk());

        clock.now = Instant.parse("2026-12-09T03:00:00Z"); // 09/12 00:00 en Argentina
        putDate("2026-12-11").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha ya no se puede cambiar: la subida de fotos ya está habilitada"));
        assertThat(reload().getEventDate()).isEqualTo(LocalDate.parse("2026-12-10"));
    }

    @Test
    @DisplayName("Wizard sin completar: no rige el bloqueo de la ventana (elegir 'mañana' se puede corregir)")
    void noLockBeforeTheWizardIsDone() throws Exception {
        putDate("2026-10-07").andExpect(status().isOk()); // la ventana de mañana ya abrió (hoy 00:00)
        putDate("2026-10-20").andExpect(status().isOk());
        assertThat(reload().getEventDate()).isEqualTo(LocalDate.parse("2026-10-20"));
    }

    // ---------- color ----------

    @Test
    @DisplayName("Color: #RRGGBB se guarda en minúsculas; cualquier otra cosa, 400; null vuelve a la paleta por defecto")
    void color() throws Exception {
        mockMvc.perform(put(api("/branding/color")).cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"#2E4374\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.backgroundColor").value("#2e4374"));
        for (String bad : new String[]{"2e4374", "#2e437", "red", "#2e4374;}body{x"}) {
            mockMvc.perform(put(api("/branding/color")).cookie(owner).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"color\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(reload().getBackgroundColor()).isEqualTo("#2e4374");
        mockMvc.perform(put(api("/branding/color")).cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"color\":null}"))
                .andExpect(status().isOk());
        assertThat(reload().getBackgroundColor()).isNull();
    }

    // ---------- imagen ----------

    @Test
    @DisplayName("Un archivo que no es imagen se rechaza con un mensaje claro y no se sube nada")
    void nonImageIsRejected() throws Exception {
        mockMvc.perform(multipart(api("/branding/image")).file(new MockMultipartFile("file", "fondo.jpg", "image/jpeg",
                        "esto no es una imagen".getBytes(StandardCharsets.UTF_8))).cookie(owner))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no corresponde a una imagen válida")));
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString(), any());
        assertThat(reload().getBackgroundImageKey()).isNull();
    }

    @Test
    @DisplayName("Imagen: va a events/{id}/branding/{uuid}.jpg sin EXIF, con cache inmutable; reemplazar borra la anterior; no es una foto del álbum")
    void imageIsStoredReplacedAndIsNotAPhoto() throws Exception {
        byte[] withExif = jpegWithExif();

        mockMvc.perform(multipart(api("/branding/image")).file(new MockMultipartFile("file", "fondo.jpg", "image/jpeg", withExif)).cookie(owner))
                .andExpect(status().isOk());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).uploadBytes(key.capture(), bytes.capture(), eq("image/jpeg"), eq("public, max-age=31536000, immutable"));
        String firstKey = key.getValue();
        assertThat(firstKey).matches("events/" + event.getId() + "/branding/[0-9a-f-]{36}\\.jpg");
        assertThat(new String(bytes.getValue(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("GPS-SECRETO");
        assertThat(ImageIO.read(new ByteArrayInputStream(bytes.getValue()))).as("sigue siendo un JPEG válido").isNotNull();
        assertThat(reload().getBackgroundImageKey()).isEqualTo(firstKey);

        mockMvc.perform(multipart(api("/branding/image")).file(new MockMultipartFile("file", "otro.jpg", "image/jpeg", withExif)).cookie(owner))
                .andExpect(status().isOk());
        String secondKey = reload().getBackgroundImageKey();
        assertThat(secondKey).isNotEqualTo(firstKey);
        verify(storageService).deleteFile(firstKey);

        mockMvc.perform(delete(api("/branding/image")).cookie(owner)).andExpect(status().isOk());
        verify(storageService).deleteFile(secondKey);
        assertThat(reload().getBackgroundImageKey()).isNull();

        // No es una Photo: ni el álbum público ni el ZIP (que se arma con las Photo del evento) la ven.
        assertThat(photoRepository.count()).isZero();
        mockMvc.perform(get("/api/v1/events/" + event.getSlug() + "/photos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("stripJpegMetadata: saca APP1 (EXIF/XMP) y deja el resto; un archivo que no es JPEG se rechaza")
    void stripJpegMetadata() throws Exception {
        byte[] clean = ImageContent.stripJpegMetadata(jpegWithExif());
        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(ImageIO.read(new ByteArrayInputStream(clean))).isNotNull();
        assertThatThrownBy(() -> ImageContent.stripJpegMetadata(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0}))
                .isInstanceOf(InvalidFileContentException.class);
    }

    // ---------- wizard ----------

    @Test
    @DisplayName("'Ir a mi panel' sin fecha: 400. Con fecha: marca el wizard; repetirlo no mueve la marca. Después, el wizard redirige al panel")
    void completeIsIdempotent() throws Exception {
        mockMvc.perform(post(api("/wizard/complete")).cookie(owner)).andExpect(status().isBadRequest());

        putDate("2026-12-10").andExpect(status().isOk());
        mockMvc.perform(post(api("/wizard/complete")).cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.wizardCompleted").value(true));
        Instant first = reload().getWizardCompletedAt();
        assertThat(first).isEqualTo(clock.now);

        clock.now = clock.now.plusSeconds(3600);
        mockMvc.perform(post(api("/wizard/complete")).cookie(owner)).andExpect(status().isOk());
        assertThat(reload().getWizardCompletedAt()).isEqualTo(first);

        mockMvc.perform(get("/admin/eventos/" + event.getSlug() + "/wizard").cookie(owner))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/eventos/" + event.getSlug()));
        mockMvc.perform(get("/admin/eventos/" + event.getSlug()).cookie(owner)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sin completar: el panel redirige al wizard, la API da 409 WIZARD_REQUIRED y 'Mis eventos' dice 'Falta configurar'")
    void panelIsBlockedUntilDone() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + event.getSlug()).cookie(owner))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/eventos/" + event.getSlug() + "/wizard"));
        mockMvc.perform(get(api("/photos")).cookie(owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("WIZARD_REQUIRED"));
        mockMvc.perform(get("/admin/eventos/" + event.getSlug() + "/wizard").cookie(owner)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/eventos").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Falta configurar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/eventos/" + event.getSlug() + "/wizard")));
    }

    @Test
    @DisplayName("El organizador B no puede ver, completar ni editar el wizard del evento de A: siempre 404")
    void strangerGets404() throws Exception {
        mockMvc.perform(get("/admin/eventos/" + event.getSlug() + "/wizard").cookie(stranger)).andExpect(status().isNotFound());
        mockMvc.perform(put(api("/date")).cookie(stranger).contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-12-10\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(api("/branding/color")).cookie(stranger).contentType(MediaType.APPLICATION_JSON).content("{\"color\":\"#2e4374\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart(api("/branding/image")).file(new MockMultipartFile("file", "f.jpg", "image/jpeg", jpegWithExif())).cookie(stranger))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(api("/branding/image")).cookie(stranger)).andExpect(status().isNotFound());
        mockMvc.perform(post(api("/wizard/complete")).cookie(stranger)).andExpect(status().isNotFound());
        Event untouched = reload();
        assertThat(untouched.getEventDate()).isNull();
        assertThat(untouched.getBackgroundColor()).isNull();
        assertThat(untouched.getWizardCompletedAt()).isNull();
    }

    // ---------- páginas de invitado ----------

    @Test
    @DisplayName("Sin personalización, las 4 páginas salen byte a byte como el archivo; con color, llevan la paleta (la pantalla nunca)")
    void guestPages() throws Exception {
        String[][] pages = {{"", "menu"}, {"/subir", "upload"}, {"/album", "album"}, {"/mensajes", "messages"}};
        for (String[] page : pages) {
            byte[] served = mockMvc.perform(get("/e/" + event.getSlug() + page[0])).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(served).as(page[1]).isEqualTo(new ClassPathResource("guest-pages/" + page[1] + ".html").getContentAsByteArray());
        }

        Event e = reload();
        e.setBackgroundColor("#2e4374");
        eventRepository.save(e);
        for (String[] page : pages) {
            String html = mockMvc.perform(get("/e/" + event.getSlug() + page[0])).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).as(page[1])
                    .contains("/css/event-branding.css")
                    .contains("--brand-primary:#2e4374;--brand-primary-deep:#11192b;--brand-primary-light:#e8e9ee;--brand-secondary:#596a90;")
                    .contains("--accent-rgb:232, 233, 238;")
                    .contains("--brand-bg:linear-gradient(165deg,#e8e9ee 0%,#2e4374 55%,#11192b 100%);");
            assertThat(html.indexOf("event-palette")).as("antes de </head>").isLessThan(html.indexOf("</head>"));
        }
        assertThat(mockMvc.perform(get("/e/" + event.getSlug() + "/pantalla")).andReturn().getResponse().getContentAsString())
                .doesNotContain("event-branding");

        e = reload();
        e.setBackgroundImageKey("events/" + event.getId() + "/branding/0b5a4c1e-2f3d-4a6b-8c9d-0e1f2a3b4c5d.jpg");
        eventRepository.save(e);
        assertThat(mockMvc.perform(get("/e/" + event.getSlug())).andReturn().getResponse().getContentAsString())
                .contains("--brand-bg:url(\"/uploads/events/" + event.getId() + "/branding/0b5a4c1e-2f3d-4a6b-8c9d-0e1f2a3b4c5d.jpg\") center/cover no-repeat;");

        // Una clave que no tiene el formato exacto (no debería existir nunca) no llega al CSS.
        e = reload();
        e.setBackgroundImageKey("events/" + event.getId() + "/branding/x\");}body{background:red.jpg");
        eventRepository.save(e);
        assertThat(mockMvc.perform(get("/e/" + event.getSlug())).andReturn().getResponse().getContentAsString())
                .doesNotContain("background:red").contains("--brand-bg:linear-gradient(");
    }

    /** Un JPEG chico con un segmento APP1 "Exif" con un marcador de GPS falso, como el de una foto de celular. */
    private static byte[] jpegWithExif() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", plain);
        byte[] jpeg = plain.toByteArray();
        byte[] payload = "Exif\0\0GPS-SECRETO-34.6037S-58.3816W".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
