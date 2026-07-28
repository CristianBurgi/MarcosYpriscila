package com.tuapp.eventfoto.photo;

import com.tuapp.eventfoto.comment.Comment;
import com.tuapp.eventfoto.event.Event;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "photos", indexes = {
    @Index(name = "idx_photos_event_id", columnList = "event_id"),
    @Index(name = "idx_photos_approved", columnList = "is_approved")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "uploader_name", length = 150)
    private String uploaderName;

    @Builder.Default
    @Column(name = "is_approved", nullable = false)
    private boolean isApproved = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * DECISIÓN DE ARQUITECTURA Y BORRADO EN CASCADA:
     * Si se elimina una fotografía (Photo), todos los comentarios asociados a ella (Comment)
     * deben ser eliminados automáticamente tanto del contexto JPA (CascadeType.ALL + orphanRemoval = true)
     * como a nivel de base de datos (ON DELETE CASCADE en la FK de la tabla comments).
     */
    @Builder.Default
    @OneToMany(mappedBy = "photo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
