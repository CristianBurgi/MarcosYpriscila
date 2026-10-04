package com.tuapp.eventfoto.checkout;

/** Mercado Pago no respondió o rechazó la preferencia. El mensaje es para la persona; el detalle va al log sin cuerpos. */
public class PaymentGatewayException extends RuntimeException {

    public static final String USER_MESSAGE = "No pudimos conectarnos con Mercado Pago. Probá de nuevo en unos minutos";

    public PaymentGatewayException(Throwable cause) {
        super(USER_MESSAGE, cause);
    }
}
