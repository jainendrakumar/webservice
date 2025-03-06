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
 * Implements per-message-type rate limiting.
 *
 * @author JKR3
 */
@Component
public class MessageThrottler {

    @Autowired
    private ConfigurationManager configManager;

    // Counters for each message type
    private ConcurrentHashMap<String, AtomicInteger> messageCounters = new ConcurrentHashMap<>();

    /**
     * Initializes the throttler and schedules periodic counter reset.
     */
    @PostConstruct
    public void init() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::resetCounters, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Determines whether a message is allowed based on the rate limit for the given message type.
     *
     * @param messageType the message type.
     * @return true if allowed, false if rate limit exceeded.
     */
    public synchronized boolean allowMessage(String messageType) {
        int limit = Integer.parseInt(configManager.getProperty("throttle." + messageType) != null ?
                configManager.getProperty("throttle." + messageType) : "500");
        AtomicInteger counter = messageCounters.computeIfAbsent(messageType, k -> new AtomicInteger(0));
        if (counter.get() < limit) {
            counter.incrementAndGet();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Resets the message counters.
     */
    public void resetCounters() {
        messageCounters.forEach((key, value) -> value.set(0));
    }
}
