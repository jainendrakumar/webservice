package com.example.aggregation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * Main application class for the Aggregation Service.
 *
 * <p>This class bootstraps the Spring Boot application, enables scheduling for
 * periodic tasks (such as bucket flushing and input processing), and provides
 * shared beans like {@link RestTemplate} for HTTP communication with the target
 * REST endpoint.</p>
 *
 * @author jkr3
 */
@SpringBootApplication
@EnableScheduling
public class AggregationServiceApplication {

    /**
     * Entry point for the Aggregation Service Spring Boot application.
     *
     * @param args runtime arguments (not used)
     */
    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }

    /**
     * Defines a {@link RestTemplate} bean for performing synchronous HTTP requests.
     * <p>
     * This bean is used by {@code AggregatorService} to send merged JSON payloads
     * to the configured target REST URL.
     * </p>
     *
     * @return a new {@link RestTemplate} instance
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
