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
    public String newFreeEventPage() {
        return "superadmin/new-event";
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
        model.addAttribute("purchases", panelService.pendingPurchases());
        model.addAttribute("incidents", panelService.openIncidents());
        model.addAttribute("demo", demoService.stats());
        return "superadmin/events";
    }
}
