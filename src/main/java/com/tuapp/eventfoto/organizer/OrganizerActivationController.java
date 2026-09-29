package com.tuapp.eventfoto.organizer;

import com.tuapp.eventfoto.organizer.dto.ActivateAccountRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/organizer")
@RequiredArgsConstructor
public class OrganizerActivationController {

    private final OrganizerTokenService organizerTokenService;
    private final OrganizerRepository organizerRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/activate")
    @Transactional
    public ResponseEntity<Map<String, String>> activate(@Valid @RequestBody ActivateAccountRequestDTO request) {
        Organizer organizer = organizerTokenService.consume(request.token(), OrganizerTokenPurpose.ACCOUNT_ACTIVATION);
        organizer.setPasswordHash(passwordEncoder.encode(request.password()));
        organizerRepository.save(organizer);
        return ResponseEntity.ok(Map.of("email", organizer.getEmail()));
    }
}
