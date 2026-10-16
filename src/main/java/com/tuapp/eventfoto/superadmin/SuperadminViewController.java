package com.tuapp.eventfoto.superadmin;

import com.tuapp.eventfoto.demo.DemoService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.UploadWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Controller
@RequestMapping("/superadmin")
@RequiredArgsConstructor
public class SuperadminViewController {

    private static final Fmt FMT = new Fmt();

    private final SuperadminPanelService panelService;
    private final DemoService demoService;

    /** Fechas en hora de Argentina (Railway corre en UTC). */
    public static final class Fmt {
        private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm").withZone(UploadWindow.ZONE);
        private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yy");

        public String at(Instant instant) {
            return instant == null ? "—" : AT.format(instant);
        }

        public String day(LocalDate date) {
            return date == null ? "—" : DAY.format(date);
        }
    }

    @GetMapping("/login")
    public String loginPage() {
        return "superadmin/login";
    }

    @GetMapping("/events/new")
    public String newFreeEventPage(Model model) {
        navCounts(model);
        return "superadmin/new-event";
    }

    @GetMapping("/pagos")
    public String paymentsPage(Model model) {
        var purchases = panelService.pendingPurchases();
        var incidents = panelService.openIncidents();
        model.addAttribute("fmt", FMT);
        model.addAttribute("purchases", purchases);
        model.addAttribute("incidents", incidents);
        model.addAttribute("pendingPayments", purchases.size() + incidents.size());
        return "superadmin/payments";
    }

    @GetMapping("/arrepentimiento")
    public String withdrawalsPage(Model model) {
        navCounts(model);
        model.addAttribute("fmt", FMT);
        model.addAttribute("withdrawals", panelService.withdrawalRequests());
        return "superadmin/withdrawals";
    }

    @GetMapping("/demo")
    public String demoPage(Model model) {
        navCounts(model);
        model.addAttribute("demo", demoService.stats());
        return "superadmin/demo";
    }

    /** El número junto a "Pagos" en el menú: compras pendientes más incidentes sin resolver. */
    private void navCounts(Model model) {
        model.addAttribute("pendingPayments", panelService.pendingPurchases().size() + panelService.openIncidents().size());
    }

    @GetMapping("/eventos")
    public String eventsPage(@RequestParam(defaultValue = "ACTIVE") SuperadminPanelService.Scope scope,
                             @RequestParam(required = false) EventOrigin origin,
                             @RequestParam(defaultValue = "0") int page,
                             Model model) {
        model.addAttribute("fmt", FMT);
        model.addAttribute("today", panelService.today());
        model.addAttribute("scope", scope);
        model.addAttribute("origin", origin);
        model.addAttribute("result", panelService.events(scope, origin, Math.max(page, 0)));
        navCounts(model);
        return "superadmin/events";
    }
}
