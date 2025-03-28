package framework.receiver;

import framework.archiver.MessageArchiver;
import framework.processor.MessageBatchProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * The {@code MessageReceiverController} class exposes REST endpoints for receiving incoming JSON messages.
 *
 * <p>This controller handles requests to the URL pattern <code>/receiver/{messageType}</code>.
 * When a message is received, it performs the following steps:
 * <ol>
 *     <li>Reads the JSON message payload.</li>
 *     <li>Archives the raw incoming message using {@link MessageArchiver}.</li>
 *     <li>Forwards the message to the {@link MessageBatchProcessor} for batching and subsequent processing.</li>
 * </ol>
 *
 * <p>
 * The message type (e.g., "LoadAttribute") is specified as a path variable in the URL.
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * POST /receiver/LoadAttribute
 * Content-Type: application/json
 *
 * {
 *   "LoadAttribute": [
 *     {
 *       "LOADID": "ABCONFLICTTEST",
 *       "LOADNAME": "Test Train",
 *       "LOADTYPE": "BOXN",
 *       "LOADSTTS": "AD",
 *       "STTSTIME": "24-03-2024 21:00:00",
 *       "STATIONFROM": "SMT",
 *       "STATIONTO": "BPL"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @see MessageArchiver
 * @see MessageBatchProcessor
 *
 * @author jkr3 (Jainendra Kumar)
 */
@RestController
@RequestMapping("/receiver")
public class MessageReceiverController {

    @Autowired
    private MessageBatchProcessor batchProcessor;

    @Autowired
    private MessageArchiver archiver;

    /**
     * Receives an incoming JSON message for the specified message type.
     *
     * <p>This method reads the message body from the HTTP request, archives it using
     * {@link MessageArchiver#archiveIncoming(String, String)}, and forwards it to the batch processor
     * via {@link MessageBatchProcessor#addMessage(String, String)}.</p>
     *
     * @param messageType the type of the message (e.g., "LoadAttribute").
     * @param request     the HTTP servlet request containing the message payload.
     * @return an HTTP 200 OK response with a confirmation message.
     */
    @PostMapping("/{messageType}")
    public ResponseEntity<String> receiveMessage(@PathVariable String messageType, HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error reading message");
        }
        String message = sb.toString();
        // Archive the raw incoming message
        archiver.archiveIncoming(messageType, message);
        // Add the message to the batch processor for grouping and eventual sending
        batchProcessor.addMessage(messageType, message);
        return ResponseEntity.ok("Message received for type: " + messageType);
    }
}
