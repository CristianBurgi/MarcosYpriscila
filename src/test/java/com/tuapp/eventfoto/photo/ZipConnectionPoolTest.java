package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.config.JwtTokenProvider;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.message.Message;
import com.tuapp.eventfoto.message.MessageRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.pdf.PdfRenderService;
import com.tuapp.eventfoto.storage.StorageService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 3: mientras se genera el PDF del libro de visitas y se transmite cada
 * foto desde R2 NO debe haber ninguna conexión del pool de base tomada por esta request.
 * Con varios organizadores bajando ZIPs a la vez, una conexión por descarga agota el pool
 * (hikari maximum-pool-size: 20).
 *
 * Sin @Transactional a propósito: una transacción de test sostendría una conexión y el
 * test mediría otra cosa. Va por MockMvc para que open-in-view participe como en producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ZipConnectionPoolTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @MockBean private StorageService storageService;
    @SpyBean private PdfRenderService pdfRenderService;

    private final List<String> samples = Collections.synchronizedList(new ArrayList<>());
    private Organizer organizer;
    private Event event;

    @BeforeEach
    void setUp() {
        cleanUp();
        organizer = organizerRepository.save(Organizer.builder().email("pool@test.com").build());
        event = eventRepository.save(Event.builder()
                .organizer(organizer).name("Evento de Prueba").slug("evento-pool-k7m2xq9p")
                .eventDate(LocalDate.now().plusDays(1)).uploadDeadline(Instant.now().plusSeconds(864000))
                .isActive(true).origin(EventOrigin.PAID).build());
        for (int i = 0; i < 3; i++) {
            photoRepository.save(Photo.builder().event(event).storageKey("photos/pool/" + i + ".jpg").uploaderName("Ana").build());
        }
        messageRepository.save(Message.builder().event(event).authorName("Beto").text("Hola").build());

        HikariPoolMXBean pool = pool();
        when(storageService.streamObject(anyString())).thenAnswer(inv -> {
            samples.add("foto " + inv.getArgument(0) + " -> activas=" + pool.getActiveConnections());
            return new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
        });
        doAnswer(inv -> {
            samples.add("render PDF -> activas=" + pool.getActiveConnections());
            return inv.callRealMethod();
        }).when(pdfRenderService).render(anyString(), any());
    }

    @AfterEach
    void cleanUp() {
        messageRepository.deleteAll();
        photoRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
        Mockito.reset(pdfRenderService);
    }

    private HikariPoolMXBean pool() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Durante el render del PDF y el streaming desde R2 hay 0 conexiones activas del pool")
    void noPoolConnectionIsHeldWhileStreamingTheZip() throws Exception {
        String token = jwtTokenProvider.generateOrganizerToken(organizer.getId(), organizer.getEmail(), organizer.getTokenVersion());

        mockMvc.perform(get("/api/v1/admin/events/" + event.getSlug() + "/photos/download-zip")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        System.out.println("POOL-MEASURE open-in-view=" + System.getProperty("spring.jpa.open-in-view", "(default)")
                + " muestras=" + samples);
        assertThat(samples).as("se tomaron muestras durante el render y el streaming").hasSize(4); // PDF + 3 fotos
        assertThat(samples).as("conexiones activas del pool durante el streaming").allMatch(s -> s.endsWith("activas=0"));
    }
}
