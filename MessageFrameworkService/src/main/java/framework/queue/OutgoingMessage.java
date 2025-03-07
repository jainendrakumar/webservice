package framework.queue;

import java.util.Objects;

/**
 * Represents an outgoing message with metadata for queuing.
 * Implements Comparable to enable priority-based ordering.
 *
 * @author JKR3
 */
public class OutgoingMessage implements Comparable<OutgoingMessage> {
    private String messageType;
    private String content;
    private int priority; // 1 (highest) to 10 (lowest)
    private long timestamp;

    public OutgoingMessage(String messageType, String content, int priority) {
        this.messageType = messageType;
        this.content = content;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
    }

    public String getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public int getPriority() {
        return priority;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Compares messages based on priority and timestamp (FIFO if same priority).
     */
    @Override
    public int compareTo(OutgoingMessage o) {
        if (this.priority != o.priority) {
            return Integer.compare(this.priority, o.priority);
        }
        return Long.compare(this.timestamp, o.timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutgoingMessage)) return false;
        OutgoingMessage that = (OutgoingMessage) o;
        return priority == that.priority &&
                timestamp == that.timestamp &&
                Objects.equals(messageType, that.messageType) &&
                Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageType, content, priority, timestamp);
    }
}
