package com.tuapp.eventfoto.withdrawal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {

    /** Para el superadmin: los más recientes primero. */
    List<WithdrawalRequest> findTop200ByOrderByCreatedAtDesc();
}
