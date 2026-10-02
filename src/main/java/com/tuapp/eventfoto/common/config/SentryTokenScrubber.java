package com.tuapp.eventfoto.common.config;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Sentry captura la URL de la request, las breadcrumbs HTTP y los mensajes de las excepciones: bajo
 * /moderar y /api/v1/moderate todo eso lleva el token. Antes de enviar, se enmascara con {@link TokenMasker}.
 */
@Configuration
public class SentryTokenScrubber {

    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> sentryTokenScrubbing() {
        return options -> {
            options.setBeforeSend((event, hint) -> scrub(event));
            options.setBeforeBreadcrumb((breadcrumb, hint) -> scrub(breadcrumb));
        };
    }

    static SentryEvent scrub(SentryEvent event) {
        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(TokenMasker.mask(request.getUrl()));
            request.setQueryString(TokenMasker.mask(request.getQueryString()));
        }
        event.setTransaction(TokenMasker.mask(event.getTransaction()));
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(TokenMasker.mask(message.getMessage()));
            message.setFormatted(TokenMasker.mask(message.getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(TokenMasker.mask(exception.getValue()));
            }
        }
        if (event.getBreadcrumbs() != null) {
            event.getBreadcrumbs().forEach(SentryTokenScrubber::scrub);
        }
        return event;
    }

    static Breadcrumb scrub(Breadcrumb breadcrumb) {
        breadcrumb.setMessage(TokenMasker.mask(breadcrumb.getMessage()));
        Map<String, Object> data = new HashMap<>(breadcrumb.getData());
        data.replaceAll((key, value) -> value instanceof String text ? TokenMasker.mask(text) : value);
        data.forEach(breadcrumb::setData);
        return breadcrumb;
    }
}
