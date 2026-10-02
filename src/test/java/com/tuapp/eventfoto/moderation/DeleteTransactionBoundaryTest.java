package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.common.config.DeletionActor;
import com.tuapp.eventfoto.message.MessageService;
import com.tuapp.eventfoto.photo.PhotoService;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Las dos entradas publicas de borrado (2 y 3 argumentos) de fotos y mensajes tienen que correr dentro de una
 * transaccion. La de 2 argumentos delega por {@code this} en la de 3: si no lleva su propio @Transactional, el proxy
 * no se aplica y cada operacion de base corre en su mini-transaccion. Esta clase NO es @Transactional (los demas tests
 * de integracion si, y lo enmascaran): se llama desde fuera de cualquier transaccion y se captura si hay una activa
 * en el punto donde se toca el storage y donde sale el SSE.
 */
class DeleteTransactionBoundaryTest extends ModeratorTestBase {

    @Autowired private PhotoService photoService;
    @Autowired private MessageService messageService;
    @SpyBean private SseBroadcaster sseBroadcaster;

    private final List<Boolean> storageInTx = new ArrayList<>();
    private final List<Boolean> sseInTx = new ArrayList<>();

    private void captureTransactionState() {
        doAnswer(call -> {
            storageInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storageService).deleteFile(anyString());
        doAnswer(call -> {
            sseInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(sseBroadcaster).broadcastPhotoDeleted(any(), any());
        doAnswer(call -> {
            sseInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(sseBroadcaster).broadcastMessageDeleted(any(), any());
    }

    @Test
    @DisplayName("deletePhoto (2 y 3 argumentos) corre en una transaccion cuando storage y SSE se tocan")
    void deletePhotoRunsInsideATransactionFromBothEntries() {
        captureTransactionState();
        var second = photo(eventA, "Carla");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("el test no abre transaccion").isFalse();

        photoService.deletePhoto(eventA.getId(), photoA.getId());                           // entrada del panel (2 args)
        photoService.deletePhoto(eventA.getId(), second.getId(), DeletionActor.MODERADOR);  // entrada del moderador (3 args)

        assertThat(storageInTx).as("deleteFile con transaccion activa").containsExactly(true, true);
        assertThat(sseInTx).as("broadcastPhotoDeleted con transaccion activa").containsExactly(true, true);
        assertThat(photoRepository.count()).isEqualTo(1); // quedo la de B
    }

    @Test
    @DisplayName("deleteMessage (2 y 3 argumentos) corre en una transaccion cuando el SSE sale")
    void deleteMessageRunsInsideATransactionFromBothEntries() {
        captureTransactionState();
        var second = messageRepository.save(com.tuapp.eventfoto.message.Message.builder().event(eventA).authorName("Dani").text("otro").build());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        messageService.deleteMessage(eventA.getId(), messageA.getId());
        messageService.deleteMessage(eventA.getId(), second.getId(), DeletionActor.MODERADOR);

        assertThat(sseInTx).as("broadcastMessageDeleted con transaccion activa").containsExactly(true, true);
        assertThat(messageRepository.count()).isEqualTo(1);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
