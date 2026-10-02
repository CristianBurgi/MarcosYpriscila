package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import com.tuapp.eventfoto.storage.StorageService;
import com.tuapp.eventfoto.testsupport.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.5 (tope C): /upload-direct (multipart por el servidor, sin presigned URL) era el único camino de subida sin
 * rate limit. Ahora tiene los mismos valores que /upload-url: 30/min por guestToken y 500/min por IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadDirectRateLimitTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private EventRepository eventRepository;
    @Autowired private OrganizerRepository organizerRepository;
    @Autowired private PhotoRepository photoRepository;
    @Autowired private GuestQuotaRepository guestQuotaRepository;
    @Autowired private RateLimiterService rateLimiterService;
    @MockBean private StorageService storageService;

    private Event event;

    @BeforeEach
    void setUp() {
        cleanUp();
        rateLimiterService.resetRateLimits();
        Organizer organizer = organizerRepository.save(Organizer.builder().email("ratelimit@test.com").build());
        event = TestEvents.unlimited(eventRepository, organizer, "evento-rate-k7m2xq9p");
        when(storageService.generatePublicUrl(any())).thenReturn("https://r2.test/foto.jpg");
    }

    @AfterEach
    void cleanUp() {
        rateLimiterService.resetRateLimits();
        photoRepository.deleteAll();
        guestQuotaRepository.deleteAll();
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    private ResultActions direct(String guestToken) throws Exception {
        return mockMvc.perform(multipart("/api/v1/events/" + event.getSlug() + "/photos/upload-direct")
                .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                .param("uploaderName", "Ana").param("guestToken", guestToken));
    }

    @Test
    @DisplayName("Por guestToken: las primeras 30 en un minuto se aceptan y la 31ª da 429; otro token sigue pudiendo")
    void thirtyFirstUploadInAMinuteFromTheSameTokenIsRejected() throws Exception {
        for (int i = 1; i <= 30; i++) {
            direct("token-rate-uno").andExpect(status().isCreated());
        }
        direct("token-rate-uno").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        direct("token-rate-dos").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Por IP: 500 subidas en un minuto con tokens distintos se aceptan y la 501ª da 429")
    void fiveHundredFirstUploadInAMinuteFromTheSameIpIsRejected() throws Exception {
        for (int i = 1; i <= 500; i++) {
            direct("token-ip-" + i).andExpect(status().isCreated());
        }
        direct("token-ip-501").andExpect(status().isTooManyRequests());
    }
}
