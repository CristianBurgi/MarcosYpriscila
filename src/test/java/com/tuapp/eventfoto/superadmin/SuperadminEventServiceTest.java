package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.Organizer;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.1 Bloque 1: única función de superadmin de este bloque -- crear un evento
 * sin costo, usando EventCreationService con origin=COURTESY.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SuperadminEventServiceTest {

    @Autowired
    private SuperadminEventService superadminEventService;
    @Autowired
    private OrganizerRepository organizerRepository;
    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        organizerRepository.deleteAll();
    }

    @Test
    @DisplayName("Email sin cuenta previa: crea Organizer nuevo y devuelve link de activación")
    void newEmailCreatesNewOrganizerWithActivationLink() {
        var result = superadminEventService.createFreeEvent("nueva@test.com", "Cumple de prueba", "Familiar");

        assertThat(result.newOrganizer()).isTrue();
        assertThat(result.activationLink()).isNotBlank();
        assertThat(organizerRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Email con cuenta existente: el evento se suma a esa cuenta, no crea un Organizer nuevo")
    void existingEmailReusesOrganizerWithoutCreatingAnotherAccount() {
        Organizer existing = organizerRepository.save(Organizer.builder().email("ya-existe@test.com").build());

        var result = superadminEventService.createFreeEvent("ya-existe@test.com", "Otro evento", "Segundo caso");

        assertThat(result.newOrganizer()).isFalse();
        assertThat(result.activationLink()).isNull();
        assertThat(result.organizer().getId()).isEqualTo(existing.getId());
        assertThat(organizerRepository.count()).isEqualTo(1); // sigue siendo uno solo, no se duplicó
    }

    @Test
    @DisplayName("Dos eventos sin costo para el mismo email ya existente quedan bajo la misma cuenta")
    void twoFreeEventsForSameExistingEmailShareOneAccount() {
        organizerRepository.save(Organizer.builder().email("repetido@test.com").build());

        var first = superadminEventService.createFreeEvent("repetido@test.com", "Evento uno", "Motivo 1");
        var second = superadminEventService.createFreeEvent("repetido@test.com", "Evento dos", "Motivo 2");

        assertThat(first.organizer().getId()).isEqualTo(second.organizer().getId());
        assertThat(organizerRepository.count()).isEqualTo(1);
        assertThat(eventRepository.count()).isEqualTo(2);
    }
}
