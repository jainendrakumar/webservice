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

    private ConcurrentHashMap<String, AtomicInteger> messageCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::resetCounters, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Checks whether sending a message of the given type is allowed based on the rate limit.
     *
     * @param messageType the message type.
     * @return true if allowed.
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
     * Resets counters for all message types.
     */
    public void resetCounters() {
        messageCounters.forEach((key, value) -> value.set(0));
    }
}
