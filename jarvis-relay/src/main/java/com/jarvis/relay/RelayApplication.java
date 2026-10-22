package com.jarvis.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point. `./gradlew bootRun` starts the relay on port 8080. */
@SpringBootApplication
public class RelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
