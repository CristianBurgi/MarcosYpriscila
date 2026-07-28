package com.tuapp.eventfoto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EventFotoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventFotoApplication.class, args);
    }

}
