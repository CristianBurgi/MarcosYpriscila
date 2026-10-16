package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.AppUrls;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase L6: la landing en "/". Mismo contexto que CheckoutPagesTest (perfil test, checkout prendido); el caso apagado
 * vive en CheckoutDisabledTest para reusar su contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LandingPageTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUrls appUrls;
    @Autowired private ITemplateEngine templateEngine;

    private Document landing() throws Exception {
        return Jsoup.parse(mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Checkout prendido: botón a /comprar, precio de app.checkout.price-ars en es-AR, sin WhatsApp")
    void checkoutOnSellsThroughCheckout() throws Exception {
        Document page = landing();
        assertThat(page.select(".hero .ctas a.btn-main, .price a.btn-main")).hasSize(2)
                .allMatch(a -> a.attr("href").equals(HomeController.BUY_PATH) && a.text().equals("Contratar evento"));
        assertThat(page.select("a[href^=https://wa.me/]")).isEmpty();
        assertThat(page.select(".amount").text()).startsWith("$50.000");
        assertThat(page.text()).contains("Pago único con Mercado Pago");
    }

    @Test
    @DisplayName("Cabecera con Ingresar -> /admin/login y la demo en la misma pestaña")
    void loginAndDemoLinks() throws Exception {
        Document page = landing();
        assertThat(page.select("nav a[href=/admin/login]").text()).isEqualTo("Ingresar");
        assertThat(page.select("a[href=/demo]")).isNotEmpty().allMatch(a -> !a.hasAttr("target"));
    }

    @Test
    @DisplayName("canonical y Open Graph absolutos desde AppUrls; la imagen para compartir es pública")
    void absoluteMetaFromAppUrls() throws Exception {
        Document page = landing();
        String base = appUrls.baseUrl();
        assertThat(page.select("link[rel=canonical]").attr("href")).isEqualTo(base + "/");
        assertThat(page.select("meta[property=og:url]").attr("content")).isEqualTo(base + "/");
        assertThat(page.select("meta[property=og:image]").attr("content")).isEqualTo(base + "/images/og-eventfoto.jpg");
        assertThat(page.select("meta[property=og:title]").attr("content")).isNotBlank();
        assertThat(page.select("meta[property=og:description]").attr("content")).isNotBlank();
        mockMvc.perform(get("/images/og-eventfoto.jpg")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Accesibilidad y peso: lang, preguntas en <details>, imágenes con alt y medidas, lazy salvo la portada")
    void accessibleAndLight() throws Exception {
        Document page = landing();
        assertThat(page.select("html").attr("lang")).startsWith("es");
        assertThat(page.select(".faq details > summary")).hasSizeGreaterThanOrEqualTo(8);
        var images = page.select("img");
        assertThat(images).isNotEmpty().allMatch(img -> !img.attr("alt").isBlank() && img.hasAttr("width") && img.hasAttr("height"));
        assertThat(images.first().hasAttr("loading")).isFalse();
        assertThat(images.subList(1, images.size())).allMatch(img -> img.attr("loading").equals("lazy"));
        for (Element img : images) {
            mockMvc.perform(get(img.attr("src"))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("robots.txt es público y deja afuera álbumes, links con credencial y paneles")
    void robotsIsPublic() throws Exception {
        mockMvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Disallow: /e/")))
                .andExpect(content().string(containsString("Disallow: /moderar/")))
                .andExpect(content().string(containsString("Disallow: /admin/")));
    }

    @Test
    @DisplayName("Checkout apagado con SUPPORT_WHATSAPP: el botón va a WhatsApp; prendido, WhatsApp solo como consulta")
    void whatsappOnlyWhenCheckoutIsOff() {
        AppUrls urls = new AppUrls("http://localhost:8080", "local");

        ExtendedModelMap off = new ExtendedModelMap();
        new HomeController(urls, false, "50000", "+54 9 381 555-0000").home(off, new MockHttpServletResponse());
        assertThat((String) off.get("whatsappUrl")).startsWith("https://wa.me/5493815550000?text=Hola%2C%20quiero%20contratar");
        assertThat(off.get("checkoutEnabled")).isEqualTo(false);
        assertThat(off.get("buyUrl")).isNull();
        assertThat(off.get("whatsappQueryUrl")).isNull();

        ExtendedModelMap on = new ExtendedModelMap();
        new HomeController(urls, true, "50000", "5493815550000").home(on, new MockHttpServletResponse());
        assertThat(on.get("whatsappUrl")).isNull();
        assertThat(on.get("buyUrl")).isEqualTo(HomeController.BUY_PATH);
        assertThat((String) on.get("whatsappQueryUrl")).isEqualTo("https://wa.me/5493815550000?text=Hola%2C%20tengo%20una%20consulta%20sobre%20EventFoto.");

        ExtendedModelMap offWithoutWhatsapp = new ExtendedModelMap();
        new HomeController(urls, false, "50000", "").home(offWithoutWhatsapp, new MockHttpServletResponse());
        assertThat(offWithoutWhatsapp.get("whatsappUrl")).isNull();
    }

    @Test
    @DisplayName("El destino de \"Contratar evento\" está solo en HomeController.BUY_PATH: el template no escribe /comprar")
    void buyPathDefinedOnce() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/landing.html"), StandardCharsets.UTF_8);
        assertThat(template).doesNotContain("/comprar").contains("th:href=\"${buyUrl}\"");
    }

    @Test
    @DisplayName("Checkout prendido con SUPPORT_WHATSAPP: el único wa.me es el link de consulta de la tarjeta de precio")
    void whatsappOnlyAsSecondaryContact() {
        // Se renderiza con el motor de este contexto: un @TestPropertySource sumaría un contexto y vaciaría la H2 compartida.
        ExtendedModelMap model = new ExtendedModelMap();
        new HomeController(appUrls, true, "50000", "5493815550000").home(model, new MockHttpServletResponse());
        Document page = Jsoup.parse(templateEngine.process("landing", new Context(Locale.forLanguageTag("es-AR"), model)));

        assertThat(page.select(".hero .ctas a.btn-main, .price a.btn-main")).hasSize(2)
                .allMatch(a -> a.attr("href").equals(HomeController.BUY_PATH) && a.text().equals("Contratar evento"));
        Elements whatsapp = page.select("a[href^=https://wa.me/]");
        assertThat(whatsapp).hasSize(1);
        assertThat(whatsapp.first().text()).isEqualTo("¿Preferís coordinar por WhatsApp?");
        assertThat(whatsapp.first().attr("href")).contains("tengo%20una%20consulta").doesNotContain("contratar");
        assertThat(whatsapp.first().closest(".price")).isNotNull();
        assertThat(whatsapp.first().hasClass("btn")).isFalse();
    }

    @Test
    @DisplayName("Precio en es-AR; uno inválido o no positivo no tumba el arranque, se oculta")
    void priceFormatting() {
        assertThat(HomeController.formatPrice("50000")).isEqualTo("50.000");
        assertThat(HomeController.formatPrice("50000.00")).isEqualTo("50.000");
        assertThat(HomeController.formatPrice("1234567.5")).isEqualTo("1.234.567,5");
        assertThat(HomeController.formatPrice("no-es-un-numero")).isNull();
        assertThat(HomeController.formatPrice("0")).isNull();
        assertThat(HomeController.formatPrice("")).isNull();
    }
}
