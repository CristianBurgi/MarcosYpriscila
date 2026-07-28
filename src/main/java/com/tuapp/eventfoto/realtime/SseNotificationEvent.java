package com.tuapp.eventfoto.realtime;

import java.time.Instant;

public record SseNotificationEvent(
    String eventType,
    Object payload,
    Instant timestamp
) {
    public static SseNotificationEvent of(String eventType, Object payload) {
        return new SseNotificationEvent(eventType, payload, Instant.now());
    }
}
