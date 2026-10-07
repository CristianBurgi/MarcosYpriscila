package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.admin.AdminViewController;
import com.tuapp.eventfoto.common.exception.EventExpiredException;
import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Las páginas HTML (invitado y panel) responden un evento inexistente con una pantalla
 * simple en vez del JSON de error de la API. Mismo cuerpo para "no existe" y "no es tuyo".
 */
@ControllerAdvice(assignableTypes = {GuestPageController.class, AdminViewController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EventPageExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String eventNotFound(Model model) {
        model.addAttribute("message", "No encontramos este evento. Revisá el link o volvé a escanear el código QR.");
        return "event-not-found";
    }

    /** Álbum vencido (invitado u organizador): no es un error, es el final del ciclo de vida del evento. */
    @ExceptionHandler(EventExpiredException.class)
    @ResponseStatus(HttpStatus.GONE)
    public String eventExpired(HttpServletRequest request, Model model) {
        model.addAttribute("organizer", request.getRequestURI().startsWith("/admin/"));
        return "event-unavailable";
    }
}
