# Message Framework Service - Detailed Design Document

## 1. Introduction

The Message Framework Service is a robust, high-performance messaging system designed to process JSON messages in real time. In addition to receiving messages via multiple REST endpoints, the service now supports advanced asynchronous processing of outgoing messages. Key enhancements include:

- **Message Queuing:** Outgoing messages are enqueued until processed. The queue supports persistence so that messages are not lost even if the receiver is temporarily unavailable.
- **Priority Levels:** Outgoing messages have an assigned priority (1–10, with 1 being the highest). When enabled, the queue orders messages by priority and timestamp (for FIFO ordering).
- **Message Filtering:** Outgoing messages can be filtered using configurable regular expressions to determine which messages are sent.
- **Scalability:** The framework supports distributed and scalable architectures. These features are configurable per interface.
- **Acknowledgement Semantics:** The sender waits for an acknowledgement from the target endpoint to ensure at-least-once or exactly-once delivery.
- **Failed Message Routing:** Unprocessable messages are routed to a separate failed queue for later inspection or retry.
- **FIFO Ordering:** FIFO ordering is maintained within each queue when enabled.
- **Encryption:** Outgoing messages can be encrypted (in transit and at rest) using a provided certificate or key.
- **Metrics & Reporting:** The system tracks throughput, latency, and error rates, and periodically writes CSV reports.
- **Database Integration:** Outgoing messages can optionally be persisted in a database (or simulated via file-based storage) to ensure durability.
- **Message Transformation:** Messages can be transformed based on a JSON schema before being sent.

All features can be enabled/disabled and configured via an external properties file. This document details the architecture, data flow, configuration, and error-handling strategies.

## 2. Architectural Overview

### Components

1. **Receiver:**
    - Exposes REST endpoints at `/receiver/{messageType}`.
    - Archives incoming raw messages and passes them to the batch processor.

2. **Batch Processor:**
    - Groups messages by type.
    - Flushes batches based on a time window or size.
    - Sends merged messages to the outgoing message queue.

3. **Outgoing Message Queue:**
    - Holds outgoing messages (wrapped as `OutgoingMessage` objects).
    - Supports priority-based ordering using a `PriorityBlockingQueue`.
    - Persists its state periodically to disk.

4. **Enhanced Message Sender:**
    - Continuously polls the outgoing message queue.
    - Applies optional filtering (via regular expressions), transformation (via JSON schema), and encryption.
    - Sends messages to target REST endpoints and waits for acknowledgements.
    - Routes failed messages to a separate failed queue.

5. **Retry Processor:**
    - Monitors a retry folder.
    - Attempts to resend messages that previously failed.

6. **Metrics & Reporting:**
    - Collects statistics (throughput, latency, error rates).
    - Generates CSV reports every minute.

7. **Throttler:**
    - Implements per-message-type rate limiting.

8. **Cleanup Manager:**
    - Performs daily cleanup tasks: zips previous day’s archives and purges old files.

9. **Persistence Module:**
    - Optionally persists outgoing messages to a database (or file-based storage) to allow recovery after failures.

10. **Transformation Engine & Encryption Utility:**
    - Transforms and encrypts messages based on configuration.

11. **Configuration Manager:**
    - Loads and hot-reloads all configuration properties.

## 3. Data Flow Diagram

[REST Endpoint] │ ▼ [Receiver] ─────────────► [Archiver (raw)] │ ▼ [Batch Processor] ──► [Merge Batch] ──► [Outgoing Queue] ──► [Enhanced Message Sender] │ ▼ [Target REST Endpoint] │ ▼ [Acknowledgement] │ ┌──────────────────────────┴────────────────────────────┐ │ │ [Failed Message] [Metrics & Reporting] │ │ [Retry Processor] [CSV Reports]


## 4. Configuration

All settings are defined in the external file `message-framework.properties` (e.g., queue persistence folder, priority settings, regex filters, encryption keys, acknowledgement mode, persistence folder, etc.). Features can be enabled/disabled per interface via properties.

## 5. Error Handling & Performance

- **Error Handling:**
    - REST call failures trigger retry logic.
    - I/O and persistence exceptions are caught and logged.
    - Unprocessable messages are routed to the failed message queue.

- **Performance:**
    - Non-blocking REST endpoints via Spring WebFlux.
    - Asynchronous processing using dedicated thread pools.
    - Efficient in-memory queues with periodic persistence for fault tolerance.
    - Optional features (filtering, transformation, encryption) can be disabled if performance is critical.

## 6. Impact on Existing Features

- **Batching and Archiving:**  
  Outgoing messages from the batch processor are now enqueued instead of being sent directly.
- **Retry & Reporting:**  
  Integrates with the new sender to provide additional fault tolerance and metrics.
- **Scalability:**  
  The asynchronous queue design and persistence allow horizontal scaling.
- **Overall:**  
  The additional processing (filtering, transformation, encryption, acknowledgement) adds a modest overhead but significantly improves reliability and flexibility. Each feature is configurable, so you can fine-tune based on workload.

---

# Detailed Implementation

Below are the complete source files with detailed documentation.

---