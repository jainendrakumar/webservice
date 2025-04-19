package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import org.apache.catalina.connector.Connector;

/**
 * Bootstraps the Spring Boot application, enables scheduling,
 * and configures two HTTP ports:
 *  • LoadPipeline on loadpipeline.server.port
 *  • MDR on mdr.server.port
 */
@SpringBootApplication
@EnableScheduling
public class AggregationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }

    /**
     * Shared RestTemplate for HTTP dispatch.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Value("${loadpipeline.server.port}")
    private int loadPipelinePort;

    @Value("${mdr.server.port}")
    private int mdrPort;

    /**
     * Configures the embedded Tomcat to listen on two ports:
     * primary = loadPipelinePort, additional = mdrPort.
     */
    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        // Set the main port:
        tomcat.setPort(loadPipelinePort);
        // Add the MDR port:
        Connector mdrConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        mdrConnector.setPort(mdrPort);
        tomcat.addAdditionalTomcatConnectors(mdrConnector);
        return tomcat;
    }
}
