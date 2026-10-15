package com.tuapp.eventfoto.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    List<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID photoId);

    /** Los de toda una página de fotos en una sola consulta (listado), con el mismo orden que la consulta por foto. */
    List<Comment> findByPhotoIdInAndIsApprovedTrueOrderByCreatedAtDescIdDesc(Collection<UUID> photoIds);

    Page<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID photoId, Pageable pageable);

    Page<Comment> findByPhotoIdOrderByCreatedAtAscIdAsc(UUID photoId, Pageable pageable);

    Optional<Comment> findByIdAndPhotoEventId(UUID id, UUID eventId);

    List<Comment> findByPhotoEventSlugAndIsApprovedTrueOrderByCreatedAtDescIdDesc(String slug);
}
