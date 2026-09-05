package com.ragforge.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Separate process boundary for at-least-once ingestion work. */
@SpringBootApplication
public class IngestionWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionWorkerApplication.class, args);
    }
}
