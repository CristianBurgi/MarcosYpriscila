package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.organizer.OrganizerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.1 (paso cero): se eliminó DataInitializer, el CommandLineRunner que
 * recreaba el evento de la boda en cada arranque -- ese fue justo el bug que dejó
 * una fila fantasma en 'events' después del primer deploy de la migración que
 * vaciaba la BD. Este test asegura que no vuelva a pasar: ni con este bloque
 * (Organizer/OrganizerToken) ni con ninguno futuro, arrancar la app no puede
 * insertar cuentas ni eventos por su cuenta.
 *
 * @DirtiesContext(BEFORE_CLASS) fuerza un ApplicationContext (y por lo tanto un
 * esquema H2 create-drop) genuinamente nuevo antes de este test -- sin esto, el
 * conteo podría ver filas que dejaron otros tests no transaccionales de la suite y
 * el resultado no probaría nada sobre el arranque en sí.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class StartupDoesNotSeedDataTest {

    @Autowired
    private OrganizerRepository organizerRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("Arrancar la app no crea ningún organizador ni ningún evento")
    void startupCreatesNoAccountsOrEvents() {
        assertThat(organizerRepository.count()).isZero();
        assertThat(eventRepository.count()).isZero();
    }
}
