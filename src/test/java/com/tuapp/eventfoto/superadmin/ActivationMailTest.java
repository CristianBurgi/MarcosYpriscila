package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.common.email.EmailService;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Fase 9.2-B: el link de activación, además de verse en el panel del superadmin, sale por mail. Después del commit
 * (si la transacción se revierte no sale nada) y sin romper nada si el envío falla. Sin @Transactional en la clase:
 * el mail sale recién cuando la transacción de verdad se confirma.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivationMailTest {

    @Autowired private SuperadminEventService superadminEventService;
    @Autowired private OrganizerRepository organizers;
    @Autowired private EventRepository events;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private EmailService emailService;

    @Test
    @DisplayName("Evento sin costo para un email sin cuenta: el mismo link del panel llega por mail, en HTML y texto")
    void freeEventForNewEmailMailsTheActivationLink() {
        String email = uniqueEmail();
        var result = superadminEventService.createFreeEvent(email, "Cumple de Sofi", "Prueba");

        EmailService.EmailMessage mail = sentMail();
        assertThat(mail.to()).isEqualTo(email);
        assertThat(mail.textBody()).contains(result.activationLink()).contains("Cumple de Sofi").doesNotContain("<");
        assertThat(mail.htmlBody()).contains("Cumple de Sofi").contains(result.activationLink().replace("&", "&amp;"));
        assertThat(mail.reference()).isEqualTo("activation:" + result.organizer().getId()).doesNotContain(email);
    }

    @Test
    @DisplayName("Link de activación regenerado: sale por mail el nuevo, sin nombre de evento")
    void regeneratedLinkIsMailed() {
        var created = superadminEventService.createFreeEvent(uniqueEmail(), "Evento", "Prueba");
        org.mockito.Mockito.clearInvocations(emailService);

        String link = superadminEventService.regenerateActivationLink(created.organizer().getId());

        EmailService.EmailMessage mail = sentMail();
        assertThat(mail.textBody()).contains(link).contains("link nuevo");
    }

    @Test
    @DisplayName("Si Resend falla, el evento y la cuenta se crean igual y el superadmin recibe el link")
    void mailFailureDoesNotBreakTheFreeEvent() {
        doThrow(new IllegalStateException("Resend caído")).when(emailService).send(any());
        String email = uniqueEmail();

        var result = superadminEventService.createFreeEvent(email, "Evento con mail caído", "Prueba");

        assertThat(result.activationLink()).isNotBlank();
        assertThat(organizers.findByEmailIgnoreCase(email)).isPresent();
        assertThat(events.findById(result.event().getId())).isPresent();
        verify(emailService).send(any());
    }

    @Test
    @DisplayName("Si la transacción se revierte, no sale ningún mail (el link no existe)")
    void noMailWhenTheTransactionRollsBack() {
        String email = uniqueEmail();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            superadminEventService.createFreeEvent(email, "Evento revertido", "Prueba");
            status.setRollbackOnly();
        });

        assertThat(organizers.findByEmailIgnoreCase(email)).isEmpty();
        verify(emailService, never()).send(any());
    }

    private EmailService.EmailMessage sentMail() {
        ArgumentCaptor<EmailService.EmailMessage> captor = ArgumentCaptor.forClass(EmailService.EmailMessage.class);
        verify(emailService).send(captor.capture());
        return captor.getValue();
    }

    private static String uniqueEmail() {
        return "activacion-" + UUID.randomUUID() + "@test.com";
    }
}
