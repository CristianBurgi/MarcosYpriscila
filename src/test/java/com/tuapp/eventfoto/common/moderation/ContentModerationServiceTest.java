package com.tuapp.eventfoto.common.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentModerationServiceTest {

    private ContentModerationServiceImpl moderationService;

    @BeforeEach
    void setUp() {
        moderationService = new ContentModerationServiceImpl();
        moderationService.init();
    }

    @Test
    @DisplayName("Permitir mensajes limpios y positivos")
    void shouldAllowCleanText() {
        assertTrue(moderationService.isAllowed("¡Muchas felicidades a los novios en su boda!"));
        assertTrue(moderationService.isAllowed("Que tengan una hermosa vida juntos. 🎉"));
    }

    @Test
    @DisplayName("Bloquear palabras ofensivas exactas")
    void shouldBlockExactProfanity() {
        assertFalse(moderationService.isAllowed("Son una mierda"));
        assertFalse(moderationService.isAllowed("sos un hdp"));
    }

    @Test
    @DisplayName("Bloquear palabras ofensivas con acentos o mayúsculas")
    void shouldBlockProfanityWithAccentsAndUppercase() {
        assertFalse(moderationService.isAllowed("Sos un ESTÚPIDO"));
        assertFalse(moderationService.isAllowed("MIÉRDÁ total"));
        assertFalse(moderationService.isAllowed("PUTO el que lee"));
    }

    @Test
    @DisplayName("Bloquear palabras ofensivas con caracteres especiales o puntos entre letras")
    void shouldBlockProfanityWithPunctuation() {
        assertFalse(moderationService.isAllowed("Sos un h.d.p. de verdad"));
        assertFalse(moderationService.isAllowed("P.U.T.O"));
    }

    @Test
    @DisplayName("Bloquear palabras ofensivas con letras repetidas 3 o más veces")
    void shouldBlockProfanityWithRepeatedCharacters() {
        assertFalse(moderationService.isAllowed("hdppppppp"));
        assertFalse(moderationService.isAllowed("mierdaaaaaaa"));
        assertFalse(moderationService.isAllowed("puuuuuuta"));
    }

    @Test
    @DisplayName("Probar método de normalización de texto directamente")
    void testNormalizeText() {
        assertEquals("hdp", moderationService.normalizeText("H.D.P..."));
        assertEquals("estupido", moderationService.normalizeText("estúpidooo"));
    }
}
