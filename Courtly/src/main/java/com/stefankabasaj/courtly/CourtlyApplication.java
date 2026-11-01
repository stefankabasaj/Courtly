package com.stefankabasaj.courtly;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.ZoneId;
import java.util.TimeZone;

@Slf4j
@SpringBootApplication
public class CourtlyApplication {

    static final String DEFAULT_TIMEZONE = "Europe/Belgrade";

    public static void main(String[] args) {
        // booking times are the venue's wall-clock time ("10:00" means 10:00 at the courts),
        // so LocalDateTime.now() has to be in the same zone. containers default to UTC,
        // which would shift the 2h cancel rule and the "slot is in the past" check by 1-2h
        String zone = System.getenv().getOrDefault("APP_TIMEZONE", DEFAULT_TIMEZONE);
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));

        SpringApplication.run(CourtlyApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("Courtly API started, timezone {}", TimeZone.getDefault().getID());
        log.info("API docs (Postman collection): Courtly-API.postman_collection.json");
    }
}
