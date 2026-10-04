package com.tuapp.eventfoto.checkout;

import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Compra nueva (pública) y recompra (organizador autenticado). Solo existen con app.checkout.enabled=true.
 * Los DTOs no tienen campo de monto ni de organizador: si el cliente los manda, se ignoran.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.enabled", havingValue = "true")
public class CheckoutController {

    public record CheckoutRequestDTO(String email, String password, String passwordConfirmation, String eventName) {
    }

    public record AdminCheckoutRequestDTO(String eventName) {
    }

    public record CheckoutResponseDTO(String redirectUrl) {
    }

    private final CheckoutService checkoutService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/api/v1/checkout")
    public ResponseEntity<CheckoutResponseDTO> checkout(@RequestBody CheckoutRequestDTO request, HttpServletRequest httpRequest) {
        rateLimiterService.checkCheckoutRateLimit(clientIpResolver.resolve(httpRequest));
        String redirectUrl = checkoutService.startNewPurchase(
                request.email(), request.password(), request.passwordConfirmation(), request.eventName());
        return ResponseEntity.ok(new CheckoutResponseDTO(redirectUrl));
    }

    /** El organizerId sale del principal autenticado (JWT validado), nunca del cuerpo. */
    @PostMapping("/api/v1/admin/checkout")
    public ResponseEntity<CheckoutResponseDTO> adminCheckout(
            @AuthenticationPrincipal UUID organizerId, @RequestBody AdminCheckoutRequestDTO request) {
        rateLimiterService.checkAdminCheckoutRateLimit(organizerId.toString());
        return ResponseEntity.ok(new CheckoutResponseDTO(checkoutService.startRepurchase(organizerId, request.eventName())));
    }
}
