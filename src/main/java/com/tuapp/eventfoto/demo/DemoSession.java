package com.tuapp.eventfoto.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** Una demo (ver V19): se crea al confirmar el wizard y vive {@link DemoService#TTL}. */
@Entity
@Table(name = "demo_session")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemoSession {

    @Id
    @Column(length = 43)
    private String sid;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "background_key")
    private String backgroundKey;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
