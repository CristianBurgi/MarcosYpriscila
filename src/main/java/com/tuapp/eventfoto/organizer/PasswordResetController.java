package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.email.MailExecutor;
import com.tuapp.eventfoto.organizer.dto.ActivateAccountRequestDTO;
import com.tuapp.eventfoto.organizer.dto.PasswordResetRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Recuperación de contraseña del organizador. Público (no hay sesión): va bajo /api/v1/organizer como la activación,
 * no bajo /api/v1/admin (que exige sesión y pasa por la resolución de evento).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizer/password-reset")
public class PasswordResetController {

    static final String REQUEST_ACCEPTED_MESSAGE =
            "Si hay una cuenta con ese email, te mandamos un link para elegir una contraseña nueva. Revisá tu correo (y la carpeta de spam).";

    private final PasswordResetService passwordResetService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;
    private final MailExecutor executor;

    /**
     * Siempre la misma respuesta (202 y el mismo texto), exista o no la cuenta. Buscarla, emitir el token y mandar el
     * mail pasa fuera del request, así el tiempo de respuesta tampoco lo delata.
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> request(@Valid @RequestBody PasswordResetRequestDTO request,
                                                       HttpServletRequest httpRequest) {
        String email = request.email().trim().toLowerCase();
        rateLimiterService.checkPasswordResetRequestRateLimit(clientIpResolver.resolve(httpRequest), email);
        executor.execute(() -> passwordResetService.requestReset(email));
        return ResponseEntity.accepted().body(Map.of("message", REQUEST_ACCEPTED_MESSAGE));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Map<String, String>> confirm(@Valid @RequestBody ActivateAccountRequestDTO request,
                                                       HttpServletRequest httpRequest) {
        rateLimiterService.checkPasswordResetConfirmRateLimit(clientIpResolver.resolve(httpRequest));
        String email = passwordResetService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok(Map.of("email", email));
    }
}
