package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.common.exception.ResourceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Token inexistente, mal formado o de un evento no moderable: una página de "link no válido" con 404 (la misma para todos los casos). */
@ControllerAdvice(assignableTypes = ModeratorViewController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ModeratorPageExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String linkNotValid() {
        return "moderator/not-found";
    }
}
