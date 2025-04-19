// src/main/java/com/example/aggregation/AggregationServiceApplication.java
package com.example.aggregation;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

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
 * <p>Provides a shared WebClient for non‑blocking HTTP dispatch.</p>
 */
@SpringBootApplication
@EnableScheduling
public class AggregationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }

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
     * Configures Tomcat with one primary connector (LoadPipeline)
     * and five additional connectors for the other streams.
     */
    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        // Primary connector for LoadPipeline
        tomcat.setPort(lpPort);
        // Additional connectors
        tomcat.addAdditionalTomcatConnectors(createConnector(mdrPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(laPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(mbPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(mbrPort));
        tomcat.addAdditionalTomcatConnectors(createConnector(tsuPort));
        return tomcat;
    }

    /** Helper to build an HTTP connector on the given port. */
    private Connector createConnector(int port) {
        Connector connector = new Connector(
                TomcatServletWebServerFactory.DEFAULT_PROTOCOL
        );
        connector.setPort(port);
        return connector;
    }

    /**
     * Shared non‑blocking HTTP client.
     * Used by AggregatorService for all REST dispatches.
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
