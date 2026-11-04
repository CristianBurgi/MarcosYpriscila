package com.tuapp.eventfoto.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fase 9.0: una desconexión normal de un cliente SSE (celular sin señal, pestaña cerrada)
 * no puede cortar el broadcast al resto ni terminar como error.
 */
class SseBroadcasterTest {

    private SseBroadcaster broadcaster;
    private UUID eventId;
    private SseEmitter healthy;
    private SseEmitter broken;

    @BeforeEach
    void setUp() throws IOException {
        broadcaster = new SseBroadcaster();
        eventId = UUID.randomUUID();
        healthy = mock(SseEmitter.class);
        broken = mock(SseEmitter.class);

        // El orden importa: el emitter roto va PRIMERO, así se verifica que su falla no
        // interrumpe el envío a los que vienen después.
        broadcaster.register(eventId, broken);
        broadcaster.register(eventId, healthy);
        assertThat(broadcaster.getActiveSubscribersCount(eventId)).isEqualTo(2);

        // Se rompe DESPUÉS de registrarse (el INIT le llegó bien): simula el celular que
        // perdió señal a mitad de la noche.
        doThrow(new IOException("Broken pipe")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        clearInvocations(healthy);
    }

    @Test
    @DisplayName("broadcast(): el emitter que tira IOException se remueve, los demás reciben y la excepción no se relanza")
    void broadcastRemovesBrokenEmitterAndKeepsSendingToTheRest() throws IOException {
        assertDoesNotThrow(() -> broadcaster.broadcastPhotoDeleted(eventId, UUID.randomUUID()));

        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(broadcaster.getActiveSubscribersCount(eventId)).isEqualTo(1);

        // Un segundo broadcast ya no intenta escribirle al emitter muerto.
        clearInvocations(broken);
        broadcaster.broadcastPhotoDeleted(eventId, UUID.randomUUID());
        verify(broken, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("Si el único emitter del evento falla al emitir, el evento sale del mapa (no queda una lista vacía)")
    void failedLastEmitterLeavesNoEmptyListInTheMap() throws IOException {
        var solo = new SseBroadcaster();
        UUID soloEvent = UUID.randomUUID();
        SseEmitter onlyOne = mock(SseEmitter.class);
        solo.register(soloEvent, onlyOne);
        doThrow(new IOException("Broken pipe")).when(onlyOne).send(any(SseEmitter.SseEventBuilder.class));

        solo.broadcastPhotoDeleted(soloEvent, UUID.randomUUID());
        assertThat(channels(solo)).doesNotContainKey(soloEvent);

        // Lo mismo por el heartbeat.
        UUID otherEvent = UUID.randomUUID();
        SseEmitter other = mock(SseEmitter.class);
        solo.register(otherEvent, other);
        doThrow(new IOException("Broken pipe")).when(other).send(any(SseEmitter.SseEventBuilder.class));
        solo.sendHeartbeat();
        assertThat(channels(solo)).doesNotContainKey(otherEvent);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Object, java.util.List<SseEmitter>> channels(SseBroadcaster b) {
        return (java.util.Map<Object, java.util.List<SseEmitter>>) org.springframework.test.util.ReflectionTestUtils.getField(b, "eventEmitters");
    }

    @Test
    @DisplayName("sendHeartbeat(): el emitter que tira IOException se remueve, los demás reciben el ping y la excepción no se relanza")
    void heartbeatRemovesBrokenEmitterAndKeepsSendingToTheRest() throws IOException {
        assertDoesNotThrow(() -> broadcaster.sendHeartbeat());

        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(broadcaster.getActiveSubscribersCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("broadcastPhotoDeleted() emite un evento SSE llamado PHOTO_DELETED con el photoId")
    void photoDeletedEventHasExpectedNameAndPayload() throws IOException {
        UUID photoId = UUID.randomUUID();
        var captor = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);

        broadcaster.broadcastPhotoDeleted(eventId, photoId);

        verify(healthy).send(captor.capture());
        String wire = SseTestSupport.render(captor.getValue());
        assertThat(wire).contains("event:PHOTO_DELETED");
        assertThat(wire).contains(photoId.toString());
    }
}
