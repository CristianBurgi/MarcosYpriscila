package com.tuapp.eventfoto.superadmin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/superadmin")
public class SuperadminViewController {

    @GetMapping("/login")
    public String loginPage() {
        return "superadmin/login";
    }

    @GetMapping("/events/new")
    public String newFreeEventPage() {
        return "superadmin/new-event";
    }
}
