package com.tuapp.eventfoto.photo.dto;

/**
 * Cupo de un invitado en un evento. "Sin límite" es explícito ({@code unlimited = true}) y los dos números
 * van en {@code null}: un -1 o un 0 obligaría al frontend a adivinar si es un centinela o un número real.
 */
public record GuestQuotaResponseDTO(
    boolean unlimited,
    Integer maxPhotosPerGuest,
    Integer remainingPhotos
) {
    public static GuestQuotaResponseDTO unlimitedQuota() {
        return new GuestQuotaResponseDTO(true, null, null);
    }

    public static GuestQuotaResponseDTO limited(int max, int remaining) {
        return new GuestQuotaResponseDTO(false, max, remaining);
    }
}
