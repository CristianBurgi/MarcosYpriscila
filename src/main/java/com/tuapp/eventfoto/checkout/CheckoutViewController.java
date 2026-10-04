package com.tuapp.eventfoto.checkout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * /comprar: formulario de compra nueva. /compra/retorno: a donde vuelve Mercado Pago; en este bloque es una página
 * fija. No lee ni refleja ningún parámetro de la URL (trae el external_reference), no consulta a MP, no loguea a nadie.
 */
@Controller
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class CheckoutViewController {

    @GetMapping("/comprar")
    public String buyPage() {
        return "checkout/buy";
    }

    @GetMapping("/compra/retorno")
    public String returnPage() {
        return "checkout/return";
    }
}
