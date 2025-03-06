# Message Framework Service - Detailed Design Document

## 1. Introduction

The Message Framework Service is a high-performance, configurable messaging system that receives JSON messages via multiple REST endpoints, processes them using batching logic, archives the incoming and merged messages, retries failed deliveries, generates real-time reporting, and enforces rate throttling per interface. The service dynamically loads configuration from an external properties file (with hot reload) and manages daily archival cleanup.

## 2. Objectives

- **High Throughput:** Efficiently ingest messages from up to 25 REST endpoints.
- **Dynamic Batching:** Group messages by type based on a configurable time window or count.
- **Archival:** Archive raw incoming messages and merged batches into dynamically generated folders.
- **Retry Mechanism:** Reattempt delivery for messages that failed to send.
- **Reporting:** Generate CSV reports every minute for incoming/outgoing message statistics.
- **Throttling:** Limit message processing rate per interface to avoid overloading target systems.
- **Dynamic Configuration:** Support hot-reload of external properties without restarting the service.
- **Daily Cleanup:** Zip and delete old archive files according to retention policies.

## 3. Architectural Overview

### Components
1. **Receiver:**  
   - Exposes REST endpoints (e.g., `/receiver/{messageType}`).
   - Archives raw incoming messages and forwards them to the batch processor.

2. **Batch Processor:**  
   - Groups messages by type.
   - Flushes batches based on either a time window or batch size limit.
   - Sends merged batches to the sender and archives the merged content.

3. **Archiver:**  
   - Creates dynamic folder structures based on the current date, hour, and minute.
   - Archives both raw and merged messages.

4. **Retry Processor:**  
   - Scans designated retry folders and attempts to resend failed messages.
   - Deletes messages from retry folders upon successful delivery.

5. **Reporter:**  
   - Collects statistics for incoming and outgoing messages.
   - Generates CSV files every minute with the message count and total byte information.

6. **Sender:**  
   - Sends messages to external target endpoints using Spring WebClient.
   - Records outgoing statistics via the Reporter.

7. **Configuration Manager:**  
   - Loads and hot-reloads external properties.
   - Provides configuration values to other components.

8. **Throttler:**  
   - Enforces per-message-type rate limiting.

9. **Cleanup Manager:**  
   - Performs daily archival cleanup: zips previous day’s folders and deletes files older than the retention period.

## 4. Data Flow Diagram

[REST Endpoint] --> [Receiver] --> [Batch Processor] --> [Sender] --> [Target Endpoint] | | [Archiver] [Reporter] | [Retry Processor]


## 5. Configuration

All configurable properties are defined in the external file `message-framework.properties`:
- **Port & Message Type Mapping**
- **Target Endpoints**
- **Batching Settings** (enabled, batch size, time window)
- **Archival Paths & Frequency**
- **Logging Settings**
- **Retry Folder Path**
- **Throttling Limits**
- **Reporting Paths & Frequency**
- **Daily Cleanup Settings**

## 6. Error Handling and Performance

- **Error Handling:**  
  - Parsing errors are logged and the faulty messages are discarded.
  - Failed REST calls trigger retry logic.
  - Disk I/O exceptions are caught and logged.

- **Performance:**  
  - Uses non-blocking Spring WebFlux for REST endpoints.
  - In-memory batching and asynchronous scheduling are leveraged.
  - Concurrency is handled via Java’s `ScheduledExecutorService` and concurrent collections.

## 7. Deployment

- **Build:** Maven is used to build the project.
- **Run:**  
  ```bash
  java -Dspring.config.additional-location=/path/to/message-framework.properties -jar target/message-framework-service-1.0.0.jar

