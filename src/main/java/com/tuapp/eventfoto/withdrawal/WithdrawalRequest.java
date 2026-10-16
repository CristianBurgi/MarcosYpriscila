package com.tuapp.eventfoto.withdrawal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Pedido del botón de arrepentimiento (V21). Se guarda tal cual lo escribió la persona: el HTML se escapa en cada
 * salida (th:text en la página, el mail HTML y el superadmin). Sin relación con cuentas ni eventos.
 */
@Entity
@Table(name = "withdrawal_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WithdrawalRequest {

    public enum Status { RECEIVED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(length = 1000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.RECEIVED;

    @Column(name = "handled_at")
    private Instant handledAt;

    /** Número de pedido para la persona y el superadmin: los primeros 8 caracteres del id, en mayúsculas. */
    public static String numberOf(UUID id) {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public String getNumber() {
        return numberOf(id);
    }
}
