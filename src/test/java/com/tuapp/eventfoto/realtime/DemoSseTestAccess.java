package com.tuapp.eventfoto.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Solo para tests de otros paquetes: registrar una pantalla simulada en el canal de una demo y leer lo que recibió. */
public final class DemoSseTestAccess {

    private DemoSseTestAccess() {
    }

    public static void registerDemoScreen(SseBroadcaster broadcaster, String sid, SseEmitter screen) {
        broadcaster.register(new SseBroadcaster.DemoChannel(sid), screen);
    }

    public static String render(SseEmitter.SseEventBuilder builder) {
        return SseTestSupport.render(builder);
    }
}
