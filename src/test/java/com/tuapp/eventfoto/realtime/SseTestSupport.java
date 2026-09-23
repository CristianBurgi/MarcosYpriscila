package com.tuapp.eventfoto.realtime;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.stream.Collectors;

final class SseTestSupport {

    private SseTestSupport() {
    }

    /**
     * Aproxima lo que viaja por el cable para un evento SSE: las líneas de protocolo
     * ("event:NOMBRE\ndata:") más el toString() del objeto de datos.
     */
    static String render(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .map(String::valueOf)
                .collect(Collectors.joining());
    }
}
