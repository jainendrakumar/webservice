package framework.sender;

import framework.config.ConfigurationManager;
import framework.queue.OutgoingMessage;
import framework.queue.OutgoingMessageQueue;
import framework.reporting.MessageReporter;
import framework.throttler.MessageThrottler;
import framework.transformation.MessageTransformer;
import framework.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The {@code MessageSender} class is responsible for sending outgoing messages to target REST endpoints.
 * It integrates several optional processing steps including filtering, transformation, encryption, and
 * acknowledgement handling.
 * <p>
 * Outgoing messages are retrieved from an {@link OutgoingMessageQueue} and then processed as follows:
 * <ul>
 *     <li>Optionally apply message throttling using {@link MessageThrottler}.</li>
 *     <li>Optionally transform the message using {@link MessageTransformer}.</li>
 *     <li>Optionally encrypt the message using {@link EncryptionUtil}.</li>
 *     <li>Send the processed message to the target endpoint via Spring WebClient.</li>
 *     <li>Record metrics via {@link MessageReporter}.</li>
 * </ul>
 * <p>
 * Configuration properties (such as endpoints, enabling/disabling features, encryption key, etc.)
 * are loaded via the {@link ConfigurationManager}.
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * &#64;Autowired
 * private MessageSender messageSender;
 *
 * // Enqueue a merged message for sending:
 * messageSender.enqueueOutgoingMessage("LoadAttribute", mergedMessageContent);
 *
 * // Alternatively, send a single message directly:
 * boolean sent = messageSender.sendMessageToEndpoint("LoadAttribute", messageContent);
 * </pre>
 *
 * @see OutgoingMessageQueue
 * @see MessageThrottler
 * @see MessageTransformer
 * @see EncryptionUtil
 * @see ConfigurationManager
 * @see MessageReporter
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageSender {

    @Autowired
    private ConfigurationManager configManager;

    @Autowired
    private MessageReporter reporter;

    @Autowired
    private OutgoingMessageQueue outgoingQueue;

    @Autowired
    private MessageThrottler throttler;

    @Autowired
    private MessageTransformer transformer;

    private WebClient webClient = WebClient.create();

    /**
     * Enqueues a merged message into the outgoing message queue.
     * <p>
     * The message is wrapped in an {@link OutgoingMessage} object. The priority for the message
     * is retrieved from configuration using the key {@code queue.priority.&lt;messageType&gt;} and
     * defaults to 5 if not provided.
     * </p>
     *
     * @param messageType   the type of the message.
     * @param mergedMessage the merged JSON message content.
     */
    public void enqueueOutgoingMessage(String messageType, String mergedMessage) {
        int priority = Integer.parseInt(configManager.getProperty("queue.priority." + messageType, "5"));
        OutgoingMessage message = new OutgoingMessage(messageType, mergedMessage, priority);
        // Enqueue the message asynchronously
        outgoingQueue.enqueue(message);
    }

    /**
     * Sends a message directly to the configured target REST endpoint for the given message type.
     * <p>
     * This method retrieves the endpoint URL from configuration using the key
     * {@code endpoint.&lt;messageType&gt;}. It then sends the message via an HTTP POST request using
     * Spring WebClient. If the call is successful (HTTP status 2xx), the method returns {@code true}.
     * </p>
     *
     * @param messageType the type of the message.
     * @param message     the message content to be sent.
     * @return {@code true} if the message is sent successfully; {@code false} otherwise.
     */
    public boolean sendMessageToEndpoint(String messageType, String message) {
        String endpoint = configManager.getProperty("endpoint." + messageType);
        if (endpoint == null) {
            System.err.println("No endpoint configured for message type: " + messageType);
            return false;
        }
        try {
            // Check if throttling allows sending this message.
            if (!throttler.allowMessage(messageType)) {
                System.out.println("Message throttled for type: " + messageType);
                return false;
            }

            // Optionally transform the message.
            boolean transformEnabled = Boolean.parseBoolean(configManager.getProperty("queue.transformation.enabled", "false"));
            String processedMessage = message;
            if (transformEnabled) {
                processedMessage = transformer.transform(messageType, message);
            }

            // Optionally encrypt the message.
            boolean encryptionEnabled = Boolean.parseBoolean(configManager.getProperty("queue.encryption.enabled", "false"));
            if (encryptionEnabled) {
                String encryptionKey = configManager.getProperty("queue.encryption.key", "defaultKey12345678");
                processedMessage = EncryptionUtil.encrypt(processedMessage, encryptionKey);
            }

            // Send the message using an HTTP POST request.
            webClient.post()
                    .uri(endpoint)
                    .body(Mono.just(processedMessage), String.class)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            // Record the outgoing metric.
            reporter.recordOutgoing(1, processedMessage.getBytes().length);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Processes the outgoing message queue.
     * <p>
     * This method runs in a background thread (e.g., via an executor) and continuously polls the outgoing
     * message queue for messages to be sent. For each message, it applies optional processing (transformation,
     * encryption, etc.) and then attempts to send the message to the target endpoint.
     * </p>
     */
    public void processQueue() {
        new Thread(() -> {
            while (true) {
                try {
                    OutgoingMessage msg = outgoingQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (msg != null) {
                        // Process and send the message.
                        boolean sent = sendMessageToEndpoint(msg.getMessageType(), msg.getContent());
                        if (!sent) {
                            // Handle failure (e.g., route to a retry queue).
                            System.err.println("Failed to send message: " + msg);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "QueueProcessorThread").start();
    }
}
