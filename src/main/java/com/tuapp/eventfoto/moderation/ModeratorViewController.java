package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.ModeratedEvent;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Página del moderador (pensada para celular). El token queda en la URL y la página lo lee de ahí; no se imprime en el HTML. */
@Controller
public class ModeratorViewController {

    @GetMapping("/moderar/{token}")
    public String page(@ModeratedEvent Event event, Model model) {
        model.addAttribute("eventName", event.getName());
        return "moderator/moderate";
    }
}
