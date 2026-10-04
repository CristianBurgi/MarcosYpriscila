package com.tuapp.eventfoto.checkout;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public static final String MESSAGE = "Ya tenés una cuenta con este email. Iniciá sesión para crear un nuevo evento";

    public EmailAlreadyRegisteredException() {
        super(MESSAGE);
    }
}
