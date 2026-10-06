package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.comment.CommentService;
import com.tuapp.eventfoto.comment.dto.CommentResponseDTO;
import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventAccessService;
import com.tuapp.eventfoto.event.EventSettingsService;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.event.OwnedEvent;
import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.GuestQuotaService;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.photo.dto.GuestPhotoLimitDTO;
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
    private final GuestQuotaService guestQuotaService;
    private final EventSettingsService eventSettingsService;
    private final UploadWindow uploadWindow;

    /** Con el checkout apagado no aparece el botón "Crear nuevo evento". */
    @org.springframework.beans.factory.annotation.Value("${app.checkout.enabled:false}")
    private boolean checkoutEnabled;

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
        model.addAttribute("checkoutEnabled", checkoutEnabled);
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
        // Estado inicial del selector de límite de fotos por invitado: el que tiene el evento hoy.
        Integer guestLimit = ownedEvent.getMaxPhotosPerGuest();
        model.addAttribute("guestPhotoLimit", new GuestPhotoLimitDTO(guestLimit == null, guestLimit));
        model.addAttribute("guestPhotoLimitOption", guestLimit != null ? guestLimit : guestQuotaService.getDefaultMaxPhotosPerGuest());
        model.addAttribute("moderatorLink", appUrls.moderatorUrl(ownedEvent.getModeratorToken()));
        model.addAttribute("messages", messages);
        model.addAttribute("photoComments", photoComments);
        addSettings(ownedEvent, model);

        return "admin/dashboard";
    }

    /**
     * Wizard de onboarding (Fase 9.5). Mientras no esté completo, EventAccessInterceptor manda acá toda vista
     * del panel de este evento. Completo, el wizard ya no tiene nada que hacer: se edita desde "Personalización".
     */
    @GetMapping("/eventos/{slug}/wizard")
    public String wizard(@OwnedEvent Event ownedEvent, Model model) {
        if (ownedEvent.getWizardCompletedAt() != null) {
            return "redirect:/admin/eventos/" + ownedEvent.getSlug();
        }
        model.addAttribute("slug", ownedEvent.getSlug());
        model.addAttribute("eventName", ownedEvent.getName());
        addSettings(ownedEvent, model);
        return "admin/wizard";
    }

    /** Fecha y personalización actuales, y el rango de fechas que acepta el servidor (hora de Argentina). */
    private void addSettings(Event event, Model model) {
        model.addAttribute("settings", eventSettingsService.view(event.getId()));
        model.addAttribute("minDate", uploadWindow.today());
        model.addAttribute("maxDate", uploadWindow.today().plusYears(2));
    }
}
