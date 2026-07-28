package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final EventRepository eventRepository;

    @Override
    public void run(String... args) {
        String slug = "marcos-y-priscila";

        if (!eventRepository.existsBySlug(slug)) {
            Instant eventDate = LocalDateTime.of(2026, 9, 19, 18, 0).toInstant(ZoneOffset.UTC);
            Instant uploadDeadline = LocalDateTime.of(2026, 10, 4, 23, 59).toInstant(ZoneOffset.UTC);

            Event event = Event.builder()
                    .name("Boda de Marcos y Priscila")
                    .slug(slug)
                    .eventDate(eventDate)
                    .uploadDeadline(uploadDeadline)
                    .isActive(true)
                    .build();

            eventRepository.save(event);
            log.info("Evento inicial 'Boda de Marcos y Priscila' (slug: '{}') creado exitosamente.", slug);
        }
    }
}
