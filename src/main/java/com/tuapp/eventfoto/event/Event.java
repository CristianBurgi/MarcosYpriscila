package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.organizer.Organizer;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_events_slug", columnList = "slug", unique = true),
    @Index(name = "idx_events_moderator_token", columnList = "moderator_token", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id", nullable = false)
    private Organizer organizer;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    /**
     * Fecha del evento (sin hora), nullable: un evento sin costo se crea solo con
     * nombre + email, la fecha se completa después con el wizard (ver
     * {@link #wizardCompletedAt}).
     */
    @Column(name = "event_date")
    private LocalDate eventDate;

    /** Fecha de borrado fijada por el superadmin (9.7); {@code null} = eventDate + 30 días. Ver {@link UploadWindow#deletionDate}. */
    @Column(name = "retention_override_until")
    private LocalDate retentionOverrideUntil;

    /** Reserva del recordatorio de borrado: sale una sola vez (ver EventLifecycleService). */
    @Column(name = "expiry_reminder_sent_at")
    private Instant expiryReminderSentAt;

    /** Contenido ya borrado (R2 y base). La fila se conserva para los pagos y "Mis eventos". */
    @Column(name = "purged_at")
    private Instant purgedAt;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventOrigin origin;

    /** Obligatorio (validado en el servicio de creación) cuando origin=COURTESY. */
    @Column(name = "origin_reason", length = 500)
    private String originReason;

    @Column(name = "wizard_completed_at")
    private Instant wizardCompletedAt;

    /**
     * Credencial del link de moderador (/moderar/{token}): 32 bytes aleatorios en base64url (43 caracteres).
     * Va en claro a propósito (el panel tiene que poder mostrar el link). Nunca se loguea; ver {@link ModeratorTokens}.
     */
    @Column(name = "moderator_token", nullable = false, unique = true, length = 43)
    private String moderatorToken;

    /**
     * Tope de fotos por invitado en este evento; {@code null} = sin límite. Sin default en el builder
     * a propósito: el valor de los eventos nuevos lo pone EventCreationServiceImpl desde la configuración
     * (app.guest-quota.default-max-photos-per-guest). Ver GuestQuotaService.
     */
    @Column(name = "max_photos_per_guest")
    private Integer maxPhotosPerGuest;

    /** Color elegido en el wizard ({@code #rrggbb} en minúsculas); {@code null} = paleta por defecto. Ver {@link EventPalette}. */
    @Column(name = "background_color", length = 7)
    private String backgroundColor;

    /** Imagen de fondo de las páginas de invitado ({@code events/{id}/branding/{uuid}.jpg}); no es una Photo. */
    @Column(name = "background_image_key")
    private String backgroundImageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (moderatorToken == null) {
            // Red de seguridad: EventCreationService lo setea siempre, esto cubre cualquier otro camino de alta.
            moderatorToken = ModeratorTokens.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
