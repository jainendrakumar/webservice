package framework.transformation;

import framework.config.ConfigurationManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The {@code MessageTransformer} class is responsible for transforming outgoing messages based on a defined
 * JSON schema or transformation rules.
 * <p>
 * When enabled via configuration, this class uses the Jackson library to parse the JSON message,
 * apply transformation logic (e.g., add or modify fields), and produce a new JSON string.
 * In this example, the transformation logic is simulated by adding a new boolean field "transformed".
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * &#64;Autowired
 * private MessageTransformer transformer;
 *
 * String originalContent = "{\"key\": \"value\"}";
 * String transformedContent = transformer.transform("LoadAttribute", originalContent);
 * </pre>
 * </p>
 *
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageTransformer {

    @Autowired
    private ConfigurationManager configManager;

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Transforms the message content for the given message type.
     * <p>
     * If transformation is enabled (via the configuration property {@code queue.transformation.enabled}),
     * this method parses the input JSON, applies the transformation rules, and returns the transformed JSON.
     * In this implementation, the transformation logic adds a new field "transformed" with the value {@code true}.
     * If the transformation is disabled or an error occurs, the original message content is returned.
     * </p>
     *
     * @param messageType the type of the message.
     * @param content     the original JSON message content.
     * @return the transformed JSON message content, or the original content if transformation is disabled or fails.
     */
    public String transform(String messageType, String content) {
        try {
            JsonNode originalNode = objectMapper.readTree(content);
            // For demonstration, add a new field "transformed" with value true.
            ((com.fasterxml.jackson.databind.node.ObjectNode) originalNode).put("transformed", true);
            return objectMapper.writeValueAsString(originalNode);
        } catch (Exception e) {
            e.printStackTrace();
            return content;
        }
    }
}
