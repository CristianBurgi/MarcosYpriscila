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
    @Index(name = "idx_photos_event_id", columnList = "event_id")
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

    /**
     * La key ORIGINAL de la presigned URL (antes de cualquier conversión HEIC), que no
     * cambia nunca. Distinta de storageKey, que puede ser la key final .jpg tras
     * convertir. Sirve para que POST /confirm sea seguro de reintentar (Fase 9.0 -
     * Bloque C): un reintento con la misma upload_key encuentra esta fila y la
     * devuelve, sin volver a leer/convertir storage ni cobrar cupo de más.
     */
    @Column(name = "upload_key", unique = true, length = 512)
    private String uploadKey;

    @Column(name = "uploader_name", length = 150)
    private String uploaderName;

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
