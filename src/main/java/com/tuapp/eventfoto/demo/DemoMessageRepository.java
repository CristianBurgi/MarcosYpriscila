package com.tuapp.eventfoto.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DemoMessageRepository extends JpaRepository<DemoMessage, UUID> {

    long countBySid(String sid);

    List<DemoMessage> findBySidOrderByCreatedAtDescIdDesc(String sid);

    @Modifying
    @Query("delete from DemoMessage m where m.sid = :sid")
    int deleteBySid(String sid);
}
