package com.tuapp.eventfoto.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Cálculo de las partes del ZIP con el default de 500 fotos por parte (sin base: solo la lista ordenada de IDs). */
class ZipPartPlannerTest {

    private static final int PER_PART = 500;

    private static List<UUID> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    @Test
    @DisplayName("500 fotos: entra en un solo ZIP (sin partes, el panel deja el botón único)")
    void fiveHundredIsASingleZip() {
        assertThat(ZipPartPlanner.plan(ids(500), PER_PART)).isEmpty();
        assertThat(ZipPartPlanner.plan(ids(0), PER_PART)).isEmpty();
    }

    @Test
    @DisplayName("501 fotos: 2 partes, 1–500 y 501–501; el corte de la 1 es la primera foto de la 2")
    void fiveHundredOneIsTwoParts() {
        List<UUID> ids = ids(501);
        List<ZipPartPlanner.ZipPart> parts = ZipPartPlanner.plan(ids, PER_PART);

        assertThat(parts).containsExactly(
                new ZipPartPlanner.ZipPart(1, 2, 1, 500, ids.get(0), ids.get(500)),
                new ZipPartPlanner.ZipPart(2, 2, 501, 501, ids.get(500), null));
    }

    @Test
    @DisplayName("1850 fotos: 4 partes (500, 500, 500 y 350) que cubren todas, sin huecos ni repetidas")
    void eighteenHundredFiftyIsFourParts() {
        List<UUID> ids = ids(1850);
        List<ZipPartPlanner.ZipPart> parts = ZipPartPlanner.plan(ids, PER_PART);

        assertThat(parts).extracting(ZipPartPlanner.ZipPart::firstPhoto).containsExactly(1, 501, 1001, 1501);
        assertThat(parts).extracting(ZipPartPlanner.ZipPart::lastPhoto).containsExactly(500, 1000, 1500, 1850);
        assertThat(parts).allMatch(part -> part.total() == 4);
        for (int i = 0; i < parts.size() - 1; i++) {
            assertThat(parts.get(i).to()).isEqualTo(parts.get(i + 1).from());
        }
        assertThat(parts.get(3).to()).isNull();
    }

    @Test
    @DisplayName("app.zip.photos-per-part en 0 o negativo no arranca")
    void rejectsNonPositivePhotosPerPart() {
        assertThatThrownBy(() -> new ZipPartPlanner(mock(PhotoRepository.class), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zip.photos-per-part");
    }
}
