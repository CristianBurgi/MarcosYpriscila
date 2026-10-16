package com.tuapp.eventfoto.withdrawal;

import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * /arrepentimiento: formulario HTML clásico (sin JavaScript) para revocar una compra. Público y siempre disponible,
 * no depende de app.checkout.enabled. El 429 se muestra en la misma página (el GlobalExceptionHandler responde JSON).
 */
@Controller
@RequestMapping("/arrepentimiento")
@RequiredArgsConstructor
public class WithdrawalController {

    /** Campo trampa: oculto para las personas; un bot que lo completa recibe una confirmación sin que se guarde nada. */
    static final String HONEYPOT = "sitio_web";
    private static final String VIEW = "legal/arrepentimiento";

    private final WithdrawalService withdrawalService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    public String form(Model model) {
        model.addAttribute("form", new WithdrawalService.Form("", "", null));
        model.addAttribute("errors", Map.of());
        return VIEW;
    }

    @PostMapping
    public String submit(@RequestParam(required = false) String email,
                         @RequestParam(required = false) String eventName,
                         @RequestParam(required = false) String message,
                         @RequestParam(name = HONEYPOT, required = false) String honeypot,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        WithdrawalService.Form form = WithdrawalService.clean(email, eventName, message);
        model.addAttribute("form", form);
        model.addAttribute("errors", Map.of());
        try {
            rateLimiterService.checkWithdrawalRateLimit(clientIpResolver.resolve(request));
        } catch (RateLimitExceededException e) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            model.addAttribute("rateLimited", e.getMessage());
            return VIEW;
        }
        if (honeypot != null && !honeypot.isBlank()) {
            model.addAttribute("receipt", withdrawalService.fakeReceipt());
            return VIEW;
        }
        Map<String, String> errors = WithdrawalService.validate(form);
        if (!errors.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("errors", errors);
            return VIEW;
        }
        model.addAttribute("receipt", withdrawalService.submit(form));
        return VIEW;
    }
}
