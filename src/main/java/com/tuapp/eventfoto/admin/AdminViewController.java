package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.comment.CommentService;
import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventAccessService;
import com.tuapp.eventfoto.event.OwnedEvent;
import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

/**
 * Vistas del panel del organizador. Fuera del login, todo cuelga de /admin/eventos:
 * "Mis eventos" (la lista) y /admin/eventos/{slug} (el panel de un evento). El evento
 * sale siempre de la ruta y se valida contra el organizador logueado (@OwnedEvent).
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private final PhotoService photoService;
    private final MessageService messageService;
    private final CommentService commentService;
    private final EventAccessService eventAccessService;
    private final AppUrls appUrls;

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    @GetMapping({"", "/"})
    public String root() {
        return "redirect:/admin/eventos";
    }

    @GetMapping("/eventos")
    public String myEvents(@AuthenticationPrincipal UUID organizerId, Model model) {
        model.addAttribute("events", eventAccessService.listOwnedEvents(organizerId));
        return "admin/events";
    }

    @GetMapping("/eventos/{slug}")
    public String dashboard(@OwnedEvent Event ownedEvent, Model model) {
        String slug = ownedEvent.getSlug();
        EventResponseDTO event = EventResponseDTO.fromEntity(ownedEvent);
        long totalPhotos = photoService.countTotalPhotos(slug);
        long totalMessages = messageService.countTotalMessages(slug);

        List<PhotoResponseDTO> photos = photoService.getPhotos(slug, PageRequest.of(0, 100)).getContent();
        List<MessageResponseDTO> messages = messageService.getMessages(slug, PageRequest.of(0, 100)).getContent();
        List<CommentResponseDTO> photoComments = commentService.getEventComments(slug);

        model.addAttribute("event", event);
        model.addAttribute("slug", slug);
        model.addAttribute("totalPhotos", totalPhotos);
        model.addAttribute("totalMessages", totalMessages);
        model.addAttribute("photos", photos);
        model.addAttribute("guestMenuUrl", appUrls.guestMenuUrl(slug));
        model.addAttribute("messages", messages);
        model.addAttribute("photoComments", photoComments);

        return "admin/dashboard";
    }
}
