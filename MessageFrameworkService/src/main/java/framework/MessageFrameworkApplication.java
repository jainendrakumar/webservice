package framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the Message Framework Service application.
 *
 * <p>This application is designed to process high-volume JSON messages in real time.
 * It integrates various modules including:</p>
 * <ul>
 *   <li><b>Receiver:</b> Exposes REST endpoints for message ingestion and archives raw messages.</li>
 *   <li><b>Batch Processor:</b> Groups messages by type and flushes batches based on time windows or size limits.</li>
 *   <li><b>Outgoing Message Queue:</b> Asynchronously stores outgoing messages with priority-based ordering
 *       and optional persistence to ensure messages are not lost.</li>
 *   <li><b>Enhanced Message Sender:</b> Polls the outgoing queue, applies filtering, transformation, encryption,
 *       and sends messages to target endpoints while handling acknowledgements and retries.</li>
 *   <li><b>Retry Processor:</b> Scans for failed messages and re-attempts delivery.</li>
 *   <li><b>Metrics & Reporting:</b> Collects and exposes metrics via Spring Boot Actuator and Micrometer,
 *       including message throughput, processing latency, error rates, and queue depth.</li>
 *   <li><b>Cleanup Manager:</b> Manages periodic cleanup and archiving of old messages.</li>
 *   <li><b>Configuration Manager:</b> Loads and hot-reloads external configuration, enabling dynamic feature toggling.</li>
 * </ul>
 *
 * <p>Configuration properties are provided via an external file (e.g., <code>message-framework.properties</code>)
 * that allows enabling/disabling features and adjusting parameters (batch size, time window, encryption keys, etc.)
 * without code changes or service restarts.</p>
 *
 * <h3>Deployment</h3>
 * <p>
 * To run the application, build the project with Maven and execute the following command:
 * </p>
 * <pre>
 *   java -Dspring.config.additional-location=/path/to/message-framework.properties -jar target/message-framework-service-1.0.0.jar
 * </pre>
 *
 * <p>
 * This command starts the service on the configured port (default is 8080) and loads external configurations.
 * </p>
 *
 * <h3>Testing</h3>
 * <p>
 * After deployment, you can test the REST endpoints (e.g., <code>/receiver/LoadAttribute</code>) using tools like cURL or Postman.
 * Metrics are exposed at the <code>/actuator/prometheus</code> endpoint and can be used with Prometheus and Grafana for visualization.
 * </p>
 *
 * @author jkr3 (Jainendra Kumar)
 */
@SpringBootApplication
public class MessageFrameworkApplication {

    /**
     * The main method that bootstraps the Message Framework Service application.
     *
     * <p>It leverages Spring Boot's auto-configuration to start all necessary components,
     * including REST endpoints, scheduling tasks, metrics exposure, and configuration management.
     * Once started, the service will listen for incoming JSON messages, process them according
     * to the defined pipeline, and provide operational metrics for monitoring.</p>
     *
     * @param args command-line arguments (optional).
     */
    public static void main(String[] args) {
        SpringApplication.run(MessageFrameworkApplication.class, args);
    }
}
