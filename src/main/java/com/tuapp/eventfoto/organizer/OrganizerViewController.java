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

    /** "¿Olvidaste tu contraseña?" desde el login: formulario de email (9.2-B). */
    @GetMapping("/admin/recuperar")
    public String forgotPasswordPage() {
        return "organizer/forgot-password";
    }

    /** Link del mail de recuperación: como /activar-cuenta, el token lo lee el JS de la página. */
    @GetMapping("/restablecer-contrasena")
    public String resetPasswordPage() {
        return "organizer/reset-password";
    }
}
