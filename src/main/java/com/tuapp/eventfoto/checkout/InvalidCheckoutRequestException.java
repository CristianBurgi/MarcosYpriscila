package com.tuapp.eventfoto.checkout;

public class InvalidCheckoutRequestException extends RuntimeException {

    public InvalidCheckoutRequestException(String message) {
        super(message);
    }
}
