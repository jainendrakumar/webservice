package framework.queue;

import java.util.Objects;

/**
 * Represents an outgoing message in the Message Framework Service.
 * <p>
 * Each {@code OutgoingMessage} object encapsulates the following details:
 * <ul>
 *   <li><b>messageType</b>: A string representing the type of message (e.g., "LoadAttribute", "CrewAvailability").</li>
 *   <li><b>content</b>: The message payload, typically in JSON format.</li>
 *   <li><b>priority</b>: An integer representing the priority of the message, where a lower number indicates a higher priority (e.g., 1 is highest, 10 is lowest).</li>
 *   <li><b>timestamp</b>: A long value indicating the time (in milliseconds since epoch) when the message was created.
 *       This is used for FIFO ordering when two messages have the same priority.</li>
 * </ul>
 * </p>
 * <p>
 * The class implements {@link Comparable} to allow the messages to be ordered within a priority-based queue.
 * Messages are first compared by their priority. If priorities are equal, the timestamp is used to maintain
 * the order in which the messages were received (i.e., FIFO).
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 *     // Creating an outgoing message with type "LoadAttribute", priority 1, and some JSON content
 *     OutgoingMessage message = new OutgoingMessage("LoadAttribute", "{\"key\":\"value\"}", 1);
 *
 *     // The message can be enqueued into a priority queue for asynchronous processing.
 *     priorityQueue.put(message);
 * </pre>
 *
 * @see java.lang.Comparable
 *
 * @author jkr3 (Jainendra Kumar)
 */
public class OutgoingMessage implements Comparable<OutgoingMessage> {

    /**
     * The type of the message (e.g., "LoadAttribute", "CrewAvailability").
     */
    private final String messageType;

    /**
     * The content of the message, typically a JSON payload.
     */
    private final String content;

    /**
     * The priority of the message, where a lower number indicates a higher priority.
     */
    private final int priority;

    /**
     * The timestamp (in milliseconds since epoch) when the message was created.
     * This is used to maintain FIFO order for messages with equal priority.
     */
    private final long timestamp;

    /**
     * Constructs a new {@code OutgoingMessage} with the specified details.
     *
     * @param messageType the type of the message.
     * @param content     the content of the message.
     * @param priority    the priority of the message (1 is highest, 10 is lowest).
     */
    public OutgoingMessage(String messageType, String content, int priority) {
        this.messageType = messageType;
        this.content = content;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Returns the message type.
     *
     * @return the type of the message.
     */
    public String getMessageType() {
        return messageType;
    }

    /**
     * Returns the message content.
     *
     * @return the content of the message.
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the priority of the message.
     *
     * @return the priority value.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns the timestamp when the message was created.
     *
     * @return the creation timestamp in milliseconds.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Compares this message with the specified message for order.
     * <p>
     * The comparison is based first on the message priority. If the priorities are not equal,
     * the message with the lower priority value (higher priority) comes first.
     * If the priorities are equal, the messages are compared by their timestamp, so that
     * the one created earlier comes first (FIFO).
     * </p>
     *
     * @param other the {@code OutgoingMessage} to be compared.
     * @return a negative integer, zero, or a positive integer as this message is less than,
     * equal to, or greater than the specified message.
     */
    @Override
    public int compareTo(OutgoingMessage other) {
        // Compare priorities first; lower value means higher priority.
        if (this.priority != other.priority) {
            return Integer.compare(this.priority, other.priority);
        }
        // If priorities are equal, compare timestamps to ensure FIFO order.
        return Long.compare(this.timestamp, other.timestamp);
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * Two {@code OutgoingMessage} objects are considered equal if they have the same
     * message type, content, priority, and timestamp.
     *
     * @param o the reference object with which to compare.
     * @return {@code true} if this object is the same as the {@code o} argument; {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutgoingMessage)) return false;
        OutgoingMessage that = (OutgoingMessage) o;
        return priority == that.priority &&
                timestamp == that.timestamp &&
                messageType.equals(that.messageType) &&
                content.equals(that.content);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(messageType, content, priority, timestamp);
    }

    /**
     * Returns a string representation of the outgoing message.
     *
     * @return a string containing the message type, priority, timestamp, and content.
     */
    @Override
    public String toString() {
        return "OutgoingMessage{" +
                "messageType='" + messageType + '\'' +
                ", priority=" + priority +
                ", timestamp=" + timestamp +
                ", content='" + content + '\'' +
                '}';
    }
}
