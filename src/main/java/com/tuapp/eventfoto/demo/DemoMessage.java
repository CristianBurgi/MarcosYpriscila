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

/** Mensaje del libro de visitas de una demo. Mismos largos que Message (150 y 1000). */
@Entity
@Table(name = "demo_message", uniqueConstraints = @UniqueConstraint(name = "uq_demo_message_slot", columnNames = {"sid", "slot"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemoMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 43)
    private String sid;

    @Column(nullable = false)
    private Short slot;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
