package com.stefankabasaj.courtly;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@SpringBootApplication
public class CourtlyApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourtlyApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("Courtly API started successfully");
        log.info("API docs (Postman collection): Courtly-API.postman_collection.json");
    }
}
