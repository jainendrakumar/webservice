
# Message Framework Service (Full Implementation)

This service handles high throughput message ingestion across multiple interfaces, with dynamic batching, archival, retry processing, reporting, and throttling.

## Features

- Multiple configurable REST endpoints for receiving JSON messages.
- Dynamic batching (time-window or count-based) configurable per interface.
- Full archival management: incoming, batched, hourly/minute folders.
- Automatic daily cleanup and zipping of archived files.
- Per-interface rate throttling.
- Retry mechanism to process failed messages from retry folders.
- Per-minute reporting into CSV for incoming/outgoing message count/size.
- External properties file for runtime configuration (hot reload supported).

## Folder Structure

```text
archive/incoming/yyyyMMdd/HH/mm
archive/merged/yyyyMMdd/HH/mm
retry/{messageType}/yyyyMMdd/HH/mm
logs/
reports/
```

## Configuration

Set up `message-framework.properties` externally and pass its location via:

```
java -Dspring.config.additional-location=/path/to/message-framework.properties -jar message-framework-service.jar
```

