package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.event.dto.EventSummaryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findBySlug(String slug);

    /** Pregunta de {@link OrganizerOwnerPolicy}: va a la base, no a la relación lazy organizer. */
    boolean existsByIdAndOrganizerId(UUID id, UUID organizerId);

    boolean existsBySlug(String slug);

    /** "Mis eventos": solo los del organizador, con la cantidad de fotos de cada uno. */
    @Query("""
            select new com.tuapp.eventfoto.event.dto.EventSummaryDTO(
                e.name, e.slug, e.eventDate, e.isActive,
                (select count(p) from Photo p where p.event = e))
            from Event e
            where e.organizer.id = :organizerId
            order by e.createdAt desc
            """)
    List<EventSummaryDTO> findSummariesByOrganizerId(@Param("organizerId") UUID organizerId);
}
