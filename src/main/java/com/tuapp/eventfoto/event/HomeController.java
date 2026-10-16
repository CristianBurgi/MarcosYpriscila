package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.config.AppUrls;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * "/" es la landing comercial (Fase L6). No pertenece a ningún evento: los QR apuntan a /e/{slug}.
 * El botón de compra depende del checkout: prendido va a BUY_PATH ("Contratar evento") y WhatsApp queda como link de
 * consulta junto al precio; apagado, el botón va a WhatsApp si SUPPORT_WHATSAPP está cargada; si no, solo queda la demo.
 * El precio se lee de app.checkout.price-ars aunque el checkout esté apagado.
 */
@Slf4j
@Controller
public class HomeController {

    /** Destino de los botones "Contratar evento": el formulario de compra (CheckoutViewController). */
    static final String BUY_PATH = "/comprar";
    static final String WHATSAPP_MESSAGE = "Hola, quiero contratar EventFoto para mi evento.";
    static final String WHATSAPP_QUERY_MESSAGE = "Hola, tengo una consulta sobre EventFoto.";

    private final String baseUrl;
    private final boolean checkoutEnabled;
    private final String price;
    private final String whatsappUrl;
    private final String whatsappQueryUrl;

    public HomeController(AppUrls appUrls,
                          @Value("${app.checkout.enabled:false}") boolean checkoutEnabled,
                          @Value("${app.checkout.price-ars:}") String rawPrice,
                          @Value("${app.support.whatsapp:}") String supportWhatsapp) {
        this.baseUrl = appUrls.baseUrl();
        this.checkoutEnabled = checkoutEnabled;
        this.price = formatPrice(rawPrice);
        String digits = supportWhatsapp == null ? "" : supportWhatsapp.replaceAll("\\D", "");
        this.whatsappUrl = whatsapp(digits, WHATSAPP_MESSAGE);
        this.whatsappQueryUrl = whatsapp(digits, WHATSAPP_QUERY_MESSAGE);
    }

    private static String whatsapp(String digits, String message) {
        return digits.isEmpty() ? null
                : "https://wa.me/" + digits + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * "50000" -> "50.000" (es-AR). Con el checkout apagado CheckoutSettings no valida el precio, así que uno inválido
     * no puede tumbar el arranque: la landing lo oculta y avisa en el log. Con el checkout prendido ya viene validado.
     */
    static String formatPrice(String rawPrice) {
        try {
            BigDecimal value = new BigDecimal(rawPrice == null ? "" : rawPrice.trim());
            if (value.signum() > 0) {
                NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-AR"));
                format.setMaximumFractionDigits(2);
                return format.format(value);
            }
        } catch (NumberFormatException ignored) {
            // cae al aviso de abajo
        }
        log.warn("app.checkout.price-ars (CHECKOUT_PRICE_ARS) no es un número mayor que cero: la landing no muestra el precio.");
        return null;
    }

    @GetMapping("/")
    public String home(Model model, HttpServletResponse response) {
        // La landing no tiene nada de la sesión ni del usuario: no-cache (revalida siempre) en vez del no-store de
        // Spring Security, que deja la página afuera del back/forward cache.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("checkoutEnabled", checkoutEnabled);
        model.addAttribute("price", price);
        model.addAttribute("buyUrl", checkoutEnabled ? BUY_PATH : null);
        model.addAttribute("whatsappUrl", checkoutEnabled ? null : whatsappUrl);
        model.addAttribute("whatsappQueryUrl", checkoutEnabled ? whatsappQueryUrl : null);
        return "landing";
    }
}
