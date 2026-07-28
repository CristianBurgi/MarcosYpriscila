package com.tuapp.eventfoto.admin;

import com.tuapp.eventfoto.event.EventService;
import com.tuapp.eventfoto.event.dto.EventResponseDTO;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private final PhotoService photoService;
    private final MessageService messageService;
    private final EventService eventService;

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @RequestParam(defaultValue = "marcos-y-priscila") String slug,
            Model model) {

        EventResponseDTO event = eventService.getEventBySlug(slug);
        long totalPhotos = photoService.countTotalPhotos(slug);
        long pendingCount = photoService.countPendingPhotos(slug);
        long totalMessages = messageService.countTotalMessages(slug);

        List<PhotoResponseDTO> pendingPhotos = photoService.getPendingPhotos(slug, PageRequest.of(0, 100)).getContent();

        model.addAttribute("event", event);
        model.addAttribute("slug", slug);
        model.addAttribute("totalPhotos", totalPhotos);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("totalMessages", totalMessages);
        model.addAttribute("pendingPhotos", pendingPhotos);

        return "admin/dashboard";
    }
}
