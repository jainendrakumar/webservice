package framework.filter;

import framework.config.ConfigurationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Filters outgoing messages based on regular expressions.
 * The regex pattern is configured per message type in the properties.
 *
 * @author JKR3
 */
@Component
public class MessageFilter {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Returns true if the message content passes the filter for the given message type.
     *
     * @param messageType the message type.
     * @param content     the message content.
     * @return true if the message should be sent.
     */
    public boolean filter(String messageType, String content) {
        String regex = configManager.getProperty("queue.filter.regex." + messageType);
        if (regex == null || regex.trim().isEmpty()) {
            return true;
        }
        Pattern pattern = Pattern.compile(regex);
        return pattern.matcher(content).find();
    }
}
