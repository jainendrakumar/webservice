package framework.filter;

import framework.config.ConfigurationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The {@code MessageFilter} class provides functionality to filter outgoing messages based
 * on regular expression patterns defined in the external configuration. This allows the system
 * to conditionally process messages based on their content.
 *
 * <p>
 * <b>Design Overview:</b>
 * <ul>
 *   <li>
 *     The filtering behavior is enabled or disabled via a configuration property
 *     (e.g., <code>queue.filter.enabled</code>).
 *   </li>
 *   <li>
 *     For each message type, a regular expression pattern can be defined in the properties file
 *     using the key <code>queue.filter.regex.&lt;messageType&gt;</code>. If the property is defined,
 *     messages are matched against the regex. If a match is found, the message passes the filter.
 *   </li>
 *   <li>
 *     If the regex is not defined or is empty, then no filtering is applied (i.e., the message is allowed).
 *   </li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * // Inject MessageFilter via Spring:
 * &#64;Autowired
 * private MessageFilter messageFilter;
 *
 * // Check if a message should be processed:
 * boolean shouldProcess = messageFilter.filter("LoadAttribute", jsonMessage);
 * </pre>
 * </p>
 *
 * <p>
 * <b>Configuration Example (message-framework.properties):</b>
 * <pre>
 * # Enable filtering
 * queue.filter.enabled=true
 *
 * # Define regex for "LoadAttribute" messages (for example, to ensure the message is a JSON object)
 * queue.filter.regex.LoadAttribute=^\{.*\}$
 * </pre>
 * </p>
 *
 * @author jkr3(Jainendra Kumar)
 */
@Component
public class MessageFilter {

    /**
     * The {@code ConfigurationManager} used to retrieve configuration properties.
     */
    @Autowired
    private ConfigurationManager configManager;

    /**
     * Filters the provided message content based on the regular expression configured for the given message type.
     * <p>
     * The method first checks if filtering is enabled by reading the property <code>queue.filter.enabled</code>.
     * If enabled, it retrieves the regex pattern from the property <code>queue.filter.regex.&lt;messageType&gt;</code>.
     * If a regex pattern is defined and non-empty, the method compiles the pattern and checks if the message
     * content matches the pattern. If the message matches the pattern, the method returns {@code true} indicating
     * that the message passes the filter. Otherwise, it returns {@code false}.
     * <p>
     * If filtering is not enabled or no pattern is defined, the method returns {@code true} to allow the message.
     *
     * @param messageType the type of the message (e.g., "LoadAttribute").
     * @param content     the content of the message.
     * @return {@code true} if the message passes the filter; {@code false} otherwise.
     */
    public boolean filter(String messageType, String content) {
        // Check if filtering is enabled; default is false if not specified.
        boolean filteringEnabled = Boolean.parseBoolean(
                configManager.getProperty("queue.filter.enabled", "false"));

        // If filtering is not enabled, allow all messages.
        if (!filteringEnabled) {
            return true;
        }

        // Retrieve the regular expression pattern for the given message type.
        String regex = configManager.getProperty("queue.filter.regex." + messageType, "");
        // If no regex is defined, allow the message.
        if (regex.isEmpty()) {
            return true;
        }

        // Compile the regex pattern.
        Pattern pattern = Pattern.compile(regex);
        // Check if the message content matches the pattern.
        return pattern.matcher(content).find();
    }
}
