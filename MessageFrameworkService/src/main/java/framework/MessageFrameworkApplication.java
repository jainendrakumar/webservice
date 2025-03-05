
package framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Message Framework Service.
 * This service handles receiving messages, batching, archiving, retry processing,
 * reporting, and throttling based on external properties.
 */
@SpringBootApplication
public class MessageFrameworkApplication {

    /**
     * Starts the Spring Boot application.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(MessageFrameworkApplication.class, args);
    }
}
