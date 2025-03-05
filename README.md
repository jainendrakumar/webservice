
# Message Framework Service - Detailed Design

## 1. Introduction
This document provides a comprehensive design for the **Message Framework Service**, which handles receiving, batching, archiving, retry processing, reporting, and throttling for high-throughput JSON messages arriving at multiple REST endpoints.

---

## 2. Objectives
- High throughput message ingestion.
- Dynamic batching and grouping (time window and count-based).
- Configurable through an external properties file.
- Automatic message archival (incoming & batched).
- Periodic reporting to capture message statistics.
- Retry mechanism for failed messages.
- Daily archival cleanup with ZIP creation.
- Log rotation and configurable logging.

---

## 3. Architectural Overview

### 3.1 Core Components
| Component | Responsibility |
|---|---|
| Message Receiver | Exposes REST endpoints, receives JSON messages. |
| Message Processor | Handles batching, grouping, and dispatching to target services. |
| Archiver | Handles hourly and minute-wise archiving of both incoming and batched messages. |
| Retry Processor | Reads from retry folders and resubmits failed messages. |
| Reporter | Captures per-minute message statistics into CSV files. |
| Throttler | Limits message throughput per interface. |
| Configuration Manager | Dynamically reloads external properties file at intervals. |
| Cleanup Manager | Handles daily folder cleanup and ZIP creation. |

---

## 4. Data Flow Diagram
```text
[REST Endpoint] --> [Receiver] --> [Batcher] --> [Archiver] --> [Sender] --> [Target Service]
                                                |
                                            [Reporter]
                                                |
                                            [Retry Processor]
```

---

## 5. Configuration - Properties File
Example `message-framework.properties`:
```properties
port.6501=LoadAttribute
port.6502=CrewAvailability

endpoint.LoadAttribute=http://target-system/processLoad
endpoint.CrewAvailability=http://target-system/processCrew

batch.LoadAttribute.enabled=true
batch.LoadAttribute.size=100
batch.LoadAttribute.timeWindowSec=3

archive.incoming.path=/data/archive/incoming
archive.merged.path=/data/archive/merged
archive.zip.path=/data/archive/zips

archive.frequency.minute=10
log.path=/data/logs
log.rotate.size=100MB
log.enabled=true

zip.cleanup.enabled=true
zip.cleanup.time=01:00
zip.retain.days=7

throttle.LoadAttribute=500

report.path=/data/reports
report.frequency.minute=1
```

---

## 6. Class Diagram
```text
+----------------+
| MessageReceiver|
+----------------+
        |
        v
+------------------+
| MessageProcessor |
+------------------+
        |
+-----------------+
|   Batcher       |
+-----------------+
        |
+------------------+
| Archiver        |
+------------------+
        |
+-----------------+
|   Sender        |
+-----------------+

+-----------------+
|   Reporter      |
+-----------------+

+-----------------+
|RetryProcessor   |
+-----------------+

+------------------+
| ConfigManager   |
+------------------+

+------------------+
| CleanupManager  |
+------------------+
```

---

## 7. Component Details

### 7.1 MessageReceiver (REST Controller)
- Listens on multiple ports (6501-6525).
- Each port maps to a message type.
- Accepts single JSON message per request.
- Validates and passes to `MessageProcessor`.

### 7.2 MessageProcessor
- Adds message to type-specific batch.
- Flushes batch if:
    - Batch size exceeds limit.
    - Time window expires.

### 7.3 Batcher
- Maintains in-memory map of `type -> list of messages`.
- Scheduled task to flush all batches at configured intervals.

### 7.4 Archiver
- Saves incoming and batched messages to disk.
- Folders created dynamically: `incoming/yyyyMMdd/HH/mm`.
- Runs background job every 10 minutes.

### 7.5 Sender
- Sends batch (or single message if batching is disabled) to target REST service.
- Reads target URLs from properties.

### 7.6 RetryProcessor
- Monitors `retry/{type}/yyyyMMdd/HH/mm` folders.
- Retries messages if failures occur.

### 7.7 Reporter
- Records `incoming` and `outgoing` message count and size per minute.
- Writes to CSV files.

### 7.8 Throttler
- Applies per-type rate limiting.
- Configurable via properties.

### 7.9 ConfigManager
- Periodically reloads properties file.
- Applies new settings dynamically (batching, throttling, logging, etc.).

### 7.10 CleanupManager
- At daily configurable time, zips previous day’s folders.
- Deletes older files if retention limit is reached.

---

## 8. Folder Structure
```
archive/incoming/yyyyMMdd/HH/mm
archive/merged/yyyyMMdd/HH/mm
retry/{messageType}/yyyyMMdd/HH/mm
logs/
reports/
```

---

## 9. Logging
- Logs to `logs/application.log`.
- Rotates by size (100MB) or daily.
- Controlled via properties.

---

## 10. Error Handling
| Type | Handling |
|---|---|
| Message Parsing Error | Log and discard message. |
| REST Call Failure | Archive to retry folder and retry later. |
| Disk I/O Failure | Log critical error and attempt retries. |
| Property Load Failure | Use previous config or exit if first load. |

---

## 11. Performance Considerations
- Non-blocking REST handling (Spring WebFlux).
- In-memory batching for speed.
- Concurrent writers for archiving.
- Buffered I/O for disk operations.
- Thread pool tuning for batch flush and sender.

---

## 12. Deployment
- Packaged as runnable JAR.
- Config placed externally.
- Command to run:
```
java -Dspring.config.additional-location=/path/to/message-framework.properties -jar message-framework-service.jar
```
