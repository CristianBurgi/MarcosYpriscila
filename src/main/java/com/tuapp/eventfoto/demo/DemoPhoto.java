package com.tuapp.eventfoto.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Foto subida en una demo. El unique(sid, slot) también se declara acá para que la H2 de los tests lo aplique. */
@Entity
@Table(name = "demo_photo", uniqueConstraints = @UniqueConstraint(name = "uq_demo_photo_slot", columnNames = {"sid", "slot"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemoPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 43)
    private String sid;

    @Column(nullable = false)
    private Short slot;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
