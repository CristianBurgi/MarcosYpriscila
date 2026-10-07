package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.util.SlugGenerator;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.photo.GuestQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
public class EventCreationServiceImpl implements EventCreationService {

    private final EventRepository eventRepository;
    private final SlugGenerator slugGenerator;
    private final GuestQuotaService guestQuotaService;

    @Override
    @Transactional
    public Event createEvent(Organizer organizer, String name, EventOrigin origin, String originReason) {
        if (organizer == null) {
            throw new IllegalArgumentException("El evento necesita un organizador");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("El nombre del evento no puede estar vacío");
        }
        if (origin == EventOrigin.COURTESY && (originReason == null || originReason.isBlank())) {
            throw new IllegalArgumentException("Un evento de cortesía necesita un motivo");
        }

        String slug = slugGenerator.generate(name.trim());

        Event event = Event.builder()
                .organizer(organizer)
                .name(name.trim())
                .slug(slug)
                .eventDate(null)
                .isActive(true)
                .origin(origin)
                .moderatorToken(ModeratorTokens.generate())
                .maxPhotosPerGuest(guestQuotaService.getDefaultMaxPhotosPerGuest())
                .originReason(origin == EventOrigin.COURTESY ? originReason.trim() : null)
                .build();

        Event saved = eventRepository.save(event);
        log.info("Evento '{}' (slug '{}') creado para organizer {} -- origen: {}{}",
                saved.getName(), saved.getSlug(), organizer.getId(), origin,
                origin == EventOrigin.COURTESY ? " (motivo: " + saved.getOriginReason() + ")" : "");
        return saved;
    }
}
