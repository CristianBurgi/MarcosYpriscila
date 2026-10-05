package com.tuapp.eventfoto.checkout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * /comprar: formulario de compra nueva. /compra/retorno: a donde vuelve Mercado Pago. El servidor no lee ningún
 * parámetro de la URL (trae payment_id y external_reference): los toma el JS de la página, los saca de la barra y los
 * manda por POST a /api/v1/checkout/confirm y /status. No emite cookies ni loguea a nadie (sin login automático).
 */
@Controller
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class CheckoutViewController {

    @GetMapping("/comprar")
    public String buyPage() {
        return "checkout/buy";
    }

    /** WhatsApp de contacto (solo dígitos, con código de país) para el caso de incidente; vacío si no está configurado. */
    @org.springframework.beans.factory.annotation.Value("${app.support.whatsapp:}")
    private String supportWhatsapp;

    @GetMapping("/compra/retorno")
    public String returnPage(org.springframework.ui.Model model) {
        String digits = supportWhatsapp == null ? "" : supportWhatsapp.replaceAll("\\D", "");
        model.addAttribute("supportWhatsappUrl", digits.isEmpty() ? null : "https://wa.me/" + digits);
        return "checkout/return";
    }
}
