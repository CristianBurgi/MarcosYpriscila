package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.ModeratorTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModeratorTokenService {

    private final EventRepository eventRepository;
    private final ModeratorStreamRegistry streamRegistry;

    /**
     * Genera un token nuevo (el anterior deja de resolver) y, una vez confirmada la transacción, cierra los
     * streams SSE abiertos con el link viejo. Devuelve el token nuevo; el llamador es quien lo muestra.
     */
    @Transactional
    public String regenerate(UUID eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ResourceNotFoundException("Evento no encontrado"));
        String newToken = ModeratorTokens.generate();
        event.setModeratorToken(newToken);
        eventRepository.saveAndFlush(event);
        String slug = event.getSlug();
        log.info("Link de moderador regenerado para el evento '{}' (el anterior dejó de funcionar)", slug);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                int closed = streamRegistry.closeAll(eventId);
                if (closed > 0) {
                    log.info("Se cerraron {} stream(s) de moderador abiertos con el link anterior del evento '{}'", closed, slug);
                }
            }
        });
        return newToken;
    }
}
