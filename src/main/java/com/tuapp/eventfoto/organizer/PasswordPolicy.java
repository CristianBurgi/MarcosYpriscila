package com.tuapp.eventfoto.organizer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Única regla de contraseñas de la app (checkout y activación de cuenta). Mínimo 8 caracteres; máximo 72 BYTES en
 * UTF-8 porque BCrypt ignora en silencio todo lo que pasa de ahí (dos contraseñas largas con el mismo comienzo serían
 * la misma); no puede ser el email ni una contraseña obvia.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "1234567890", "87654321", "11111111", "00000000", "password", "password1",
            "password123", "contraseña", "contrasena", "contraseña1", "contraseña123", "qwertyui", "qwerty123",
            "qwertyuiop", "abcd1234", "abc12345", "iloveyou", "admin123", "12341234", "eventfoto", "boca1234",
            "argentina", "bocajuniors", "riverplate");

    private PasswordPolicy() {
    }

    /** Devuelve el motivo del rechazo, listo para mostrarle a la persona, o vacío si la contraseña sirve. */
    public static Optional<String> violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of("La contraseña no puede estar vacía");
        }
        if (password.length() < MIN_LENGTH) {
            return Optional.of("La contraseña tiene que tener al menos " + MIN_LENGTH + " caracteres");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("La contraseña es demasiado larga (máximo " + MAX_BYTES + " caracteres)");
        }
        String lower = password.toLowerCase();
        if (COMMON.contains(lower) || password.chars().distinct().count() == 1) {
            return Optional.of("Esa contraseña es muy fácil de adivinar. Elegí otra");
        }
        if (email != null && !email.isBlank() && lower.equals(email.trim().toLowerCase())) {
            return Optional.of("La contraseña no puede ser igual a tu email");
        }
        return Optional.empty();
    }
}
