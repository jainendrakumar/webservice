// src/main/java/com/example/aggregation/AggregationServiceApplication.java
package com.example.aggregation;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
// Removed: import org.springframework.web.reactive.function.client.WebClient;

/**
 * Main entry point for the Aggregation Service.
 *
 * <p>Enables scheduled tasks and configures six HTTP connectors,
 * one for each message stream:</p>
 * <ul>
 *   <li>LoadPipeline (port ${loadpipeline.server.port})</li>
 *   <li>MultiDestinationRake (port ${mdr.server.port})</li>
 *   <li>LoadAttribute (port ${loadattribute.server.port})</li>
 *   <li>MaintenanceBlock (port ${maintenanceblock.server.port})</li>
 *   <li>MaintenanceBlockResource (port ${maintenanceblockresource.server.port})</li>
 *   <li>TrainServiceUpdateActual (port ${trainserviceupdate.server.port})</li>
 * </ul>
 *
 * <p>It also enables scheduled tasks and exposes a shared RestTemplate
 * for synchronous dispatch to downstream systems.</p>
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.1.0
 * @since 2025-04-28 // Updated version and date
 */
@SpringBootApplication
@EnableScheduling
public class AggregationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }

    // ───────────── Injected HTTP Ports for Each Pipeline ─────────────

    @Value("${loadpipeline.server.port}")
    private int lpPort;

    @Value("${mdr.server.port}")
    private int mdrPort;

    @Value("${loadattribute.server.port}")
    private int laPort;

    @Value("${maintenanceblock.server.port}")
    private int mbPort;

    @Value("${maintenanceblockresource.server.port}")
    private int mbrPort;

    @Value("${trainserviceupdate.server.port}")
    private int tsuPort;

    /**
     * Configures embedded Tomcat with six HTTP connectors.
     * <p>
     * The main connector uses the LoadPipeline port, and five additional connectors
     * are added for other ingestion endpoints. This allows each controller to
     * listen on its own dedicated port.
     *
     * @return customized servlet container
     */
    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();

        // Primary port for LoadPipeline
        tomcat.setPort(lpPort);

        // Add extra ports for other pipelines
        tomcat.addAdditionalTomcatConnectors(createConnector(mdrPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(laPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(mbPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(mbrPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(tsuPort));

        return tomcat;
    }

    /**
     * Helper method to create an HTTP connector for a given port.
     *
     * @param port port number to listen on
     * @return configured Tomcat connector
     */
    private Connector createConnector(int port) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(port);
        return connector;
    }

    /**
     * Shared RestTemplate bean used across the application.
     * <p>
     * This is used for dispatching processed batches to REST endpoints synchronously.
     *
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        return restTemplate();
    }

    /**
     * Shared RestTemplate bean used across the application.
     * <p>
     * This is used for dispatching processed batches to REST endpoints synchronously.
     *
     * @param builder RestTemplateBuilder provided by Spring Boot
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Consider adding configurations like timeouts if needed
        // return builder.setConnectTimeout(Duration.ofSeconds(10)).setReadTimeout(Duration.ofSeconds(10)).build();
        return builder.build();
    }

    /* Removed WebClient bean
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
    */
}
