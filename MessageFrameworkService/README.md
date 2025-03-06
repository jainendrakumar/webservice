
# Message Framework Service - Complete Working Implementation

This is the complete working version of the **Message Framework Service**.

## Features
- High throughput message ingestion via REST.
- Dynamic batching (time-window & count-based).
- Configurable retry mechanism for failed messages.
- Per-minute message statistics (incoming & outgoing) in CSV format.
- Per-interface rate limiting.
- Automatic archival, cleanup & ZIP management.
- Full hot-reload support for configuration changes.

## Build
```
mvn clean install
```

## Run
```
java -Dspring.config.additional-location=/path/to/message-framework.properties -jar target/message-framework-service-1.0.0.jar
```

