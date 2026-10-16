package com.tuapp.eventfoto.event;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Páginas legales: públicas, con su h1 y la fecha de LegalController; la landing las enlaza desde el footer. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalPagesTest {

    @Autowired private MockMvc mockMvc;

    private Document page(String path) throws Exception {
        return Jsoup.parse(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @CsvSource({"/terminos, Términos y condiciones", "/privacidad, Política de privacidad"})
    @DisplayName("200 sin login, un solo h1, fecha de LegalController y sin los comentarios de revisión legal")
    void publicWithHeading(String path, String heading) throws Exception {
        Document page = page(path);
        assertThat(page.select("h1")).hasSize(1).first().extracting(h -> h.text()).isEqualTo(heading);
        assertThat(page.select("html").attr("lang")).isEqualTo("es-AR");
        assertThat(page.select(".updated").text()).isEqualTo("Última actualización: " + LegalController.LAST_UPDATED);
        assertThat(page.select("meta[name=description]").attr("content")).isNotBlank();
        // Cada link del índice apunta a una sección que existe.
        assertThat(page.select(".toc a")).isNotEmpty().allMatch(a -> page.getElementById(a.attr("href").substring(1)) != null);
        assertThat(page.html()).doesNotContain("REVISIÓN LEGAL");
    }

    @Test
    @DisplayName("El footer de la landing enlaza a /terminos y /privacidad")
    void landingFooterLinks() throws Exception {
        Document landing = page("/");
        assertThat(landing.select("footer a[href=/terminos]").text()).isEqualTo("Términos y condiciones");
        assertThat(landing.select("footer a[href=/privacidad]").text()).isEqualTo("Política de privacidad");
    }
}
