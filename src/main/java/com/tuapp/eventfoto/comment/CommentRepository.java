package com.tuapp.eventfoto.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    List<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID photoId);

    Page<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDescIdDesc(UUID photoId, Pageable pageable);

    Page<Comment> findByPhotoIdOrderByCreatedAtAscIdAsc(UUID photoId, Pageable pageable);

    Optional<Comment> findByIdAndPhotoEventId(UUID id, UUID eventId);

    List<Comment> findByPhotoEventSlugAndIsApprovedTrueOrderByCreatedAtDescIdDesc(String slug);
}
