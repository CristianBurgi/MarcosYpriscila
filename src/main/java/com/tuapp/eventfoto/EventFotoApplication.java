package com.tuapp.eventfoto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@Slf4j
@SpringBootApplication
@EnableScheduling
public class EventFotoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventFotoApplication.class, args);
    }

    @Bean
    public CommandLineRunner logActiveProfiles(Environment env) {
        return args -> {
            String[] activeProfiles = env.getActiveProfiles();
            if (activeProfiles.length == 0) {
                log.info("Perfiles de Spring activos: [default]");
            } else {
                log.info("Perfiles de Spring activos: {}", Arrays.toString(activeProfiles));
            }
        };
    }
}

