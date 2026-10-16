package com.tuapp.eventfoto.event;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Términos y condiciones y política de privacidad: páginas públicas enlazadas desde el footer de la landing. */
@Controller
public class LegalController {

    /** Fecha que muestran las dos páginas. Al cambiar cualquiera de los textos, se actualiza acá. */
    static final String LAST_UPDATED = "16 de octubre de 2026";

    @GetMapping("/terminos")
    public String terms(Model model) {
        model.addAttribute("lastUpdated", LAST_UPDATED);
        return "legal/terminos";
    }

    @GetMapping("/privacidad")
    public String privacy(Model model) {
        model.addAttribute("lastUpdated", LAST_UPDATED);
        return "legal/privacidad";
    }
}
