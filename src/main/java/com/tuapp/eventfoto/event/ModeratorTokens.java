package com.tuapp.eventfoto.event;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Generación y validación de formato del token de moderador (32 bytes aleatorios, base64url sin relleno = 43 caracteres). */
public final class ModeratorTokens {

    public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private ModeratorTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
