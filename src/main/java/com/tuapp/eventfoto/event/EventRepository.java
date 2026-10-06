package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventSummaryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findBySlug(String slug);

    /** Pregunta de {@link OrganizerOwnerPolicy}: va a la base, no a la relación lazy organizer. */
    boolean existsByIdAndOrganizerId(UUID id, UUID organizerId);

    boolean existsBySlug(String slug);

    /** Resolución del link de moderador: igualdad exacta sobre el índice único. */
    Optional<Event> findByModeratorToken(String moderatorToken);

    /** "Mis eventos": solo los del organizador, con la cantidad de fotos de cada uno. */
    @Query("""
            select new com.tuapp.eventfoto.event.dto.EventSummaryDTO(
                e.name, e.slug, e.eventDate, e.isActive, case when e.wizardCompletedAt is not null then true else false end,
                (select count(p) from Photo p where p.event = e))
            from Event e
            where e.organizer.id = :organizerId
            order by e.createdAt desc
            """)
    List<EventSummaryDTO> findSummariesByOrganizerId(@Param("organizerId") UUID organizerId);

    /**
     * Cambia solo el límite de fotos por invitado ({@code null} = sin límite) con un UPDATE puntual, así no
     * pisa otros campos del evento con una copia vieja de la entidad (el panel puede tenerla desactualizada).
     */
    @Modifying
    @Query("update Event e set e.maxPhotosPerGuest = :max where e.id = :id")
    int updateMaxPhotosPerGuest(@Param("id") UUID id, @Param("max") Integer max);

    // --- Wizard y personalización (EventSettingsService): UPDATEs puntuales, igual que el de arriba ---

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.eventDate = :date where e.id = :id")
    int updateEventDate(@Param("id") UUID id, @Param("date") LocalDate date);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.backgroundColor = :color where e.id = :id")
    int updateBackgroundColor(@Param("id") UUID id, @Param("color") String color);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.backgroundImageKey = :key where e.id = :id")
    int updateBackgroundImageKey(@Param("id") UUID id, @Param("key") String key);

    @Query("select e.backgroundImageKey from Event e where e.id = :id")
    String findBackgroundImageKey(@Param("id") UUID id);

    /** Solo la primera vez: repetir "Ir a mi panel" no mueve la marca. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.wizardCompletedAt = :now where e.id = :id and e.wizardCompletedAt is null")
    int markWizardCompleted(@Param("id") UUID id, @Param("now") Instant now);
}
