package framework.transformation;

import framework.config.ConfigurationManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Transforms outgoing messages based on a JSON schema.
 * The schema and transformation rules are defined in the properties file.
 *
 * @author JKR3
 */
@Component
public class MessageTransformer {

    @Autowired
    private ConfigurationManager configManager;

    private ObjectMapper mapper = new ObjectMapper();

    /**
     * Transforms the message for the given type.
     * For demonstration, adds a "transformed" flag to the JSON.
     *
     * @param messageType the message type.
     * @param content     the original message content.
     * @return the transformed message.
     */
    public String transform(String messageType, String content) {
        try {
            JsonNode original = mapper.readTree(content);
            ((com.fasterxml.jackson.databind.node.ObjectNode) original).put("transformed", true);
            return mapper.writeValueAsString(original);
        } catch (Exception e) {
            e.printStackTrace();
            return content;
        }
    }
}
