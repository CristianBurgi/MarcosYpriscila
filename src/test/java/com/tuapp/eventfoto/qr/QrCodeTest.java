package com.tuapp.eventfoto.qr;

import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.event.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QrCodeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QrCodeService qrCodeService;

    @MockBean
    private EventService eventService;

    @Test
    @DisplayName("Debe generar exitosamente una imagen PNG de código QR")
    void shouldGenerateQrCodePngBytes() {
        String targetUrl = "https://boda-marcos-y-priscila.up.railway.app/menu.html?slug=marcos-y-priscila";
        byte[] qrBytes = qrCodeService.generateQrCodePng(targetUrl, 300, 300);

        assertNotNull(qrBytes);
        assertTrue(qrBytes.length > 100);
        // Verificar firma de archivo PNG (0x89 0x50 0x4E 0x47 en los primeros bytes)
        assertEquals((byte) 0x89, qrBytes[0]);
        assertEquals((byte) 0x50, qrBytes[1]);
        assertEquals((byte) 0x4E, qrBytes[2]);
        assertEquals((byte) 0x47, qrBytes[3]);
    }

    @Test
    @DisplayName("Endpoint GET /api/v1/events/{slug}/qr debe responder con imagen PNG")
    void shouldReturnQrCodePngFromEndpoint() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventResponseDTO eventDto = new EventResponseDTO(eventId, "Boda de Marcos y Priscila", "marcos-y-priscila", Instant.now(), null, true, Instant.now());
        when(eventService.getEventBySlug(anyString())).thenReturn(eventDto);

        mockMvc.perform(get("/api/v1/events/marcos-y-priscila/qr")
                        .param("size", "350"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"qr-marcos-y-priscila.png\""));
    }
}
