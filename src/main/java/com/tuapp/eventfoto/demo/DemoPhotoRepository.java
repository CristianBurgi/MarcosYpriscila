package com.tuapp.eventfoto.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DemoPhotoRepository extends JpaRepository<DemoPhoto, UUID> {

    long countBySid(String sid);

    List<DemoPhoto> findBySidOrderByCreatedAtDescIdDesc(String sid);

    @Modifying
    @Query("delete from DemoPhoto p where p.sid = :sid")
    int deleteBySid(String sid);
}
