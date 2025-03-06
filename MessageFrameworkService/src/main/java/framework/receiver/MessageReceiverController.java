package framework.receiver;

import framework.processor.MessageBatchProcessor;
import framework.archiver.MessageArchiver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for receiving incoming JSON messages.
 * Exposes an endpoint at /receiver/{messageType} to receive messages for a given type.
 *
 * @author JKR3
 */
@RestController
@RequestMapping("/receiver")
public class MessageReceiverController {

    @Autowired
    private MessageBatchProcessor batchProcessor;

    @Autowired
    private MessageArchiver archiver;

    /**
     * Receives a JSON message for the specified message type.
     * Archives the raw message and adds it to the batch processor.
     *
     * @param messageType the type of the message (e.g., LoadAttribute)
     * @param message     the JSON payload
     * @return HTTP 200 OK with a confirmation message
     */
    @PostMapping("/{messageType}")
    public ResponseEntity<String> receiveMessage(@PathVariable String messageType, @RequestBody String message) {
        // Archive the raw incoming message
        archiver.archiveIncoming(messageType, message);
        // Add message to the batch processor
        batchProcessor.addMessage(messageType, message);
        return ResponseEntity.ok("Message received for type: " + messageType);
    }
}
