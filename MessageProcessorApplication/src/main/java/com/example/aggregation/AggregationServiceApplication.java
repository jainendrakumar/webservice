package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import org.apache.catalina.connector.Connector;

/**
 * Bootstraps the Spring Boot application, enables scheduling,
 * and configures two HTTP ports:
 *  - LoadPipeline on loadpipeline.server.port
 *  - MDR         on mdr.server.port
 * Also provides a non-blocking WebClient.
 */
@SpringBootApplication
@EnableScheduling
public class AggregationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }

    /** Primary port for LoadPipeline ingestion. */
    @Value("${loadpipeline.server.port}")
    private int lpPort;

    /** Secondary port for MDR ingestion. */
    @Value("${mdr.server.port}")
    private int mdrPort;

    /**
     * Configures embedded Tomcat with two connectors:
     * primary = lpPort, additional = mdrPort.
     */
    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        // Primary connector uses server.port (lpPort)
        tomcat.setPort(lpPort);

        // Additional connector for MDR
        Connector mdrConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        mdrConnector.setPort(mdrPort);
        tomcat.addAdditionalTomcatConnectors(mdrConnector);

        return tomcat;
    }

    /**
     * Shared non-blocking HTTP client (WebClient).
     * Used by AggregatorService for dispatch.
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
