package com.tuapp.eventfoto.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    List<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID photoId);

    Page<Comment> findByPhotoIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID photoId, Pageable pageable);

    Page<Comment> findByPhotoIdOrderByCreatedAtAsc(UUID photoId, Pageable pageable);

    List<Comment> findByPhotoEventSlugAndIsApprovedTrueOrderByCreatedAtDesc(String slug);
}
