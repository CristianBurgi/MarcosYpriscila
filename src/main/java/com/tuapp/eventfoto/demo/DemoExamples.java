package com.tuapp.eventfoto.demo;

import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Contenido fijo de toda demo: 10 fotos (static/img/demo, sin metadatos) y mensajes de ejemplo para el ticker. Las
 * fechas son viejas y fijas: lo del visitante siempre es más nuevo y sale primero en el álbum y en la pantalla.
 */
final class DemoExamples {

    static final int PHOTO_COUNT = 10;

    private static final Instant BASE = Instant.parse("2026-01-01T03:00:00Z");

    private static final List<String[]> MESSAGES = List.of(
            new String[]{"La mesa 4", "¡Qué noche hermosa! Gracias por hacernos parte."},
            new String[]{"Los primos", "¡Que vivan los novios! Los queremos un montón."},
            new String[]{"Los del laburo", "La pista no se vació en toda la noche. ¡Qué fiestón!"},
            new String[]{"Una amiga de la facu", "Brindo por muchos años más de risas juntos."},
            new String[]{"Tu tía", "Me hicieron llorar en el vals, ¡no vale!"},
            new String[]{"Los vecinos", "Gracias por la invitación, la comida estuvo de diez."},
            new String[]{"El grupo del fútbol", "¡Felicitaciones! Se les nota lo felices que están."});

    static final List<PhotoResponseDTO> PHOTOS = IntStream.rangeClosed(1, PHOTO_COUNT)
            .mapToObj(i -> {
                String file = String.format("/img/demo/demo-%02d.webp", i);
                return new PhotoResponseDTO(id("photo-" + i), null, file, file, "Invitado",
                        BASE.minusSeconds(60L * i), 0, List.of());
            })
            .toList();

    static final List<MessageResponseDTO> TICKER = IntStream.range(0, MESSAGES.size())
            .mapToObj(i -> new MessageResponseDTO(id("message-" + i), null, MESSAGES.get(i)[0], MESSAGES.get(i)[1],
                    true, BASE.minusSeconds(60L * (i + 1))))
            .toList();

    private DemoExamples() {
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("eventfoto-demo-" + name).getBytes(StandardCharsets.UTF_8));
    }
}
