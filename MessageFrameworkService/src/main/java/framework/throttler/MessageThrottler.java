package framework.throttler;

import framework.config.ConfigurationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code MessageThrottler} class enforces per-message-type rate limiting to prevent overloading
 * of target endpoints.
 * <p>
 * It maintains a counter for each message type and uses a scheduled task to reset the counters every second.
 * The rate limit for each message type is defined in the configuration with keys like
 * {@code throttle.&lt;messageType&gt;} (e.g., {@code throttle.LoadAttribute}).
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * &#64;Autowired
 * private MessageThrottler throttler;
 *
 * if (throttler.allowMessage("LoadAttribute")) {
 *     // Proceed with sending the message.
 * }
 * </pre>
 * </p>
 *
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageThrottler {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * A thread-safe map that holds counters for each message type.
     */
    private ConcurrentHashMap<String, AtomicInteger> messageCounters = new ConcurrentHashMap<>();

    /**
     * Initializes the throttler by scheduling a task to reset all message counters every second.
     */
    @PostConstruct
    public void init() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::resetCounters, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Determines whether sending a message of the specified type is allowed based on the configured rate limit.
     * <p>
     * The method retrieves the rate limit from configuration using the key {@code throttle.&lt;messageType&gt;}.
     * If the current counter for that message type is below the rate limit, it increments the counter and returns {@code true}.
     * Otherwise, it returns {@code false} indicating that the message should be throttled.
     * </p>
     *
     * @param messageType the type of the message.
     * @return {@code true} if the message is allowed to be sent; {@code false} otherwise.
     */
    public synchronized boolean allowMessage(String messageType) {
        int limit = Integer.parseInt(configManager.getProperty("throttle." + messageType, "500"));
        AtomicInteger counter = messageCounters.computeIfAbsent(messageType, k -> new AtomicInteger(0));
        if (counter.get() < limit) {
            counter.incrementAndGet();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Resets all message counters to zero.
     * <p>
     * This method is scheduled to run every second, allowing the rate limit to be enforced on a per-second basis.
     * </p>
     */
    public void resetCounters() {
        messageCounters.forEach((key, value) -> value.set(0));
    }
}
