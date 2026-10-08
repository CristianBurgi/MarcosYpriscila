package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.InvalidEventSettingsException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Checklist previa del panel (Fase 9.8). Bajo /api/v1/admin/events/{slug}: rol ORGANIZER y EventAccessInterceptor
 * (el evento de otro organizador da 404). Marcar y desmarcar son idempotentes y responden las claves marcadas.
 */
@RestController
@RequestMapping("/api/v1/admin/events/{slug}/checklist/{item}")
@RequiredArgsConstructor
public class AdminChecklistController {

    private final EventRepository eventRepository;

    @PutMapping
    public Map<String, List<String>> mark(@OwnedEvent Event event, @PathVariable String item) {
        eventRepository.markChecklistItem(event.getId(), parse(item).mask);
        return marked(event);
    }

    @DeleteMapping
    public Map<String, List<String>> unmark(@OwnedEvent Event event, @PathVariable String item) {
        eventRepository.unmarkChecklistItem(event.getId(), parse(item).mask);
        return marked(event);
    }

    private static ChecklistItem parse(String key) {
        return ChecklistItem.fromKey(key).orElseThrow(() -> new InvalidEventSettingsException("Casilla desconocida"));
    }

    private Map<String, List<String>> marked(Event event) {
        return Map.of("marked", ChecklistItem.marked(eventRepository.findChecklist(event.getId())));
    }
}
