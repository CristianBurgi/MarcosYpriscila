package com.tuapp.eventfoto.common.config;

/** Quién borra: solo se usa para el log, la autorización la resuelve EventAccessInterceptor. */
public enum DeletionActor {
    ORGANIZADOR("organizador"),
    MODERADOR("moderador");

    private final String label;

    DeletionActor(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
