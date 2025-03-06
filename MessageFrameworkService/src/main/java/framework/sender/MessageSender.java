package framework.sender;

import framework.config.ConfigurationManager;
import framework.reporting.MessageReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Sends messages to target endpoints using Spring WebClient.
 *
 * @author JKR3
 */
@Component
public class MessageSender {

    @Autowired
    private ConfigurationManager configManager;

    @Autowired
    private MessageReporter reporter;

    private WebClient webClient = WebClient.create();

    /**
     * Sends a message to the configured endpoint for the specified message type.
     *
     * @param messageType the message type.
     * @param message     the message content.
     * @return true if the message was sent successfully; false otherwise.
     */
    public boolean sendMessage(String messageType, String message) {
        String endpoint = configManager.getEndpointForType(messageType);
        if (endpoint == null) {
            System.err.println("No endpoint configured for message type: " + messageType);
            return false;
        }
        try {
            // Send the message via a POST request
            webClient.post()
                    .uri(endpoint)
                    .body(Mono.just(message), String.class)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            // Record outgoing statistics
            reporter.recordOutgoing(1, message.getBytes().length);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
