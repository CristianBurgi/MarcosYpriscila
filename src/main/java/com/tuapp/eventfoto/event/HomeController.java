package com.tuapp.eventfoto.event;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** "/" no pertenece a ningún evento: solo indica cómo llegar a uno. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("message", "Escaneá el código QR de tu evento para entrar.");
        return "event-not-found";
    }
}
