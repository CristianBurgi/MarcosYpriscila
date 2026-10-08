package com.tuapp.eventfoto.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface DemoSessionRepository extends JpaRepository<DemoSession, String> {

    @Query("select s.sid from DemoSession s where s.createdAt <= :cutoff")
    List<String> findExpiredSids(Instant cutoff);

    @Query("select count(s) from DemoSession s where s.createdAt > :cutoff")
    long countActive(Instant cutoff);

    @Modifying
    @Query("delete from DemoSession s where s.sid = :sid")
    int deleteBySid(String sid);
}
