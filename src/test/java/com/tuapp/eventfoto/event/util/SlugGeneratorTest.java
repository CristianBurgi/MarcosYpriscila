package com.tuapp.eventfoto.event.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugGeneratorTest {

    @Test
    @DisplayName("Normaliza acentos, ñ y mayúsculas")
    void normalizesAccentsAndCase() {
        assertThat(SlugGenerator.normalize("Cumpleaños de la tía Rosa")).isEqualTo("cumpleanos-de-la-tia-rosa");
    }

    @Test
    @DisplayName("Reemplaza caracteres no alfanuméricos por guiones y recorta los de los bordes")
    void replacesNonAlphanumericWithHyphens() {
        assertThat(SlugGenerator.normalize("  ¡Fiesta!! de los 40  ")).isEqualTo("fiesta-de-los-40");
    }

    @Test
    @DisplayName("Un nombre vacío o solo símbolos cae al fallback 'evento'")
    void blankNameFallsBackToEvento() {
        assertThat(SlugGenerator.normalize("")).isEqualTo("evento");
        assertThat(SlugGenerator.normalize("¡¡¡!!!")).isEqualTo("evento");
    }

    @Test
    @DisplayName("Un nombre muy largo se recorta a 60 caracteres (events.slug es VARCHAR(100); deja margen para el sufijo)")
    void longNameIsTruncated() {
        String longName = "Cumpleaños de quince años de la prima Antonella con toda la familia reunida en el salón del club";
        String base = SlugGenerator.normalize(longName);

        assertThat(base.length()).isLessThanOrEqualTo(60);
        assertThat(base).doesNotEndWith("-");
    }

    @Test
    @DisplayName("Con un nombre largo, el slug final (base + sufijo) entra cómodo en VARCHAR(100)")
    void generatedSlugFitsInColumn() {
        String longName = "a".repeat(300);
        String base = SlugGenerator.normalize(longName);
        String fullSlugLength = base + "-" + "x".repeat(8);

        assertThat(fullSlugLength.length()).isLessThan(100);
    }
}
