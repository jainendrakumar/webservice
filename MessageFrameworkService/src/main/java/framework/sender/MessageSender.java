package framework.sender;

import framework.config.ConfigurationManager;
import framework.queue.OutgoingMessage;
import framework.queue.OutgoingMessageQueue;
import framework.reporting.MessageReporter;
import framework.transformation.MessageTransformer;
import framework.util.EncryptionUtil;
import framework.filter.MessageFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Processes the outgoing message queue and sends messages to target REST endpoints.
 * Applies optional filtering, transformation, encryption, and handles acknowledgements.
 *
 * @author JKR3
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
    private MessageFilter messageFilter;

    @Autowired
    private MessageTransformer transformer;

    private WebClient webClient = WebClient.create();

    /**
     * Starts processing the outgoing queue in a dedicated thread.
     */
    public void processQueue() {
        new Thread(() -> {
            while (true) {
                try {
                    OutgoingMessage msg = outgoingQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (msg != null) {
                        boolean filterEnabled = Boolean.parseBoolean(configManager.getProperty("queue.filter.enabled", "false"));
                        if (filterEnabled && !messageFilter.filter(msg.getMessageType(), msg.getContent())) {
                            continue;
                        }
                        boolean transformEnabled = Boolean.parseBoolean(configManager.getProperty("queue.transformation.enabled", "false"));
                        String contentToSend = msg.getContent();
                        if (transformEnabled) {
                            contentToSend = transformer.transform(msg.getMessageType(), contentToSend);
                        }
                        boolean encryptionEnabled = Boolean.parseBoolean(configManager.getProperty("queue.encryption.enabled", "false"));
                        if (encryptionEnabled) {
                            String encryptionKey = configManager.getProperty("queue.encryption.key", "defaultKey12345678");
                            contentToSend = EncryptionUtil.encrypt(contentToSend, encryptionKey);
                        }
                        boolean sent = sendMessageToEndpoint(msg.getMessageType(), contentToSend);
                        if (!sent) {
                            // Optionally route to a failed message queue
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "QueueProcessorThread").start();
    }

    /**
     * Enqueues a merged message to the outgoing queue.
     * @param messageType the message type.
     * @param mergedMessage the merged message content.
     */
    public void enqueueOutgoingMessage(String messageType, String mergedMessage) {
        int priority = Integer.parseInt(configManager.getProperty("queue.priority." + messageType, "5"));
        OutgoingMessage message = new OutgoingMessage(messageType, mergedMessage, priority);
        outgoingQueue.enqueue(message);
    }

    /**
     * Sends a message to the target endpoint.
     * @param messageType the message type.
     * @param message the message content.
     * @return true if successfully sent.
     */
    public boolean sendMessageToEndpoint(String messageType, String message) {
        String endpoint = configManager.getProperty("endpoint." + messageType);
        if (endpoint == null) {
            System.err.println("No endpoint configured for message type: " + messageType);
            return false;
        }
        try {
            webClient.post()
                    .uri(endpoint)
                    .body(Mono.just(message), String.class)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            reporter.recordOutgoing(1, message.getBytes().length);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
