package com.tuapp.eventfoto.common.exception;

/** API del panel de un evento cuyo wizard no está completo: 409 con error = {@link #CODE}. */
public class WizardRequiredException extends RuntimeException {

    public static final String CODE = "WIZARD_REQUIRED";

    public WizardRequiredException() {
        super("Terminá de configurar el evento para usar el panel.");
    }
}
