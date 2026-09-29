package com.tuapp.eventfoto.organizer;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OrganizerViewController {

    /** El token viaja como query param y lo lee el JS de la página; no se valida acá. */
    @GetMapping("/activar-cuenta")
    public String activationPage() {
        return "organizer/activate";
    }
}
