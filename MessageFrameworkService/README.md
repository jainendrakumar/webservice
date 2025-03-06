# Message Framework Service - Complete Implementation

This project implements a fully featured Message Framework Service that:
- Receives messages via 25 configurable REST endpoints.
- Batches messages dynamically (time-window and count-based).
- Archives raw and merged messages into dynamic folder structures.
- Processes failed messages using a retry mechanism.
- Generates per-minute CSV reports of message statistics.
- Applies per-interface rate throttling.
- Supports hot-reloading of configuration from an external properties file.
- Performs daily cleanup and ZIP archival.

## Build

Run the following command in the project root:
```bash
mvn clean install

java -Dspring.config.additional-location=/path/to/message-framework.properties -jar target/message-framework-service-1.0.0.jar

Folder Structure
Archives: /data/archive/incoming, /data/archive/merged, /data/archive/zips
Retry: /data/retry
Reports: /data/reports
Logs: /data/logs