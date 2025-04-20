# Aggregation Service - Ingestion Controller Summary

## Controller Details

### Ingestion Controller Mapping

| Controller Class                     | URL Path                           | Port Config Key                      | JSON Key                 | AggregatorService Method      |
|--------------------------------------|------------------------------------|--------------------------------------|--------------------------|-------------------------------|
| LoadPipelineController               | `/receive`                         | `loadpipeline.server.port`           | LoadPipeline             | processLoadPipeline           |
| MultiDestinationRakeController       | `/receive-mdr`                     | `mdr.server.port`                    | MultiDestinationRake     | processMdr                    |
| LoadAttributeController              | `/receive-loadattribute`           | `loadattribute.server.port`          | LoadAttribute            | processLoadAttribute          |
| MaintenanceBlockController           | `/receive-maintenanceblock`        | `maintenanceblock.server.port`       | MaintenanceBlock         | processMaintenanceBlock       |
| MaintenanceBlockResourceController   | `/receive-maintenanceblockresource`| `maintenanceblockresource.server.port`| MaintenanceBlockResource | processMaintenanceBlockResource|
| TrainServiceUpdateActualController   | `/receive-trainserviceupdate`      | `trainserviceupdate.server.port`     | TrainActual              | processTrainServiceUpdate     |

## Feature Support Matrix

| Feature                | LoadPipeline | MDR | LoadAttribute | MaintBlock | MaintBlockRes | TrainActual |
|------------------------|--------------|-----|---------------|------------|---------------|-------------|
| Per-ID Batching        | ✅            | ✅  | ❌            | ❌         | ❌           | ❌         |
| Global Batching        | ❌            | ❌  | ✅            | ✅         | ✅           | ✅         |
| Time-Based Flushing    | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Size-Based Flushing    | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Incoming Archiving     | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Merged Archiving       | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Throttling             | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Async REST Dispatch    | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| CSV Logging            | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |
| Dead Letter Queue      | ✅            | ✅  | ✅            | ✅         | ✅           | ✅         |

## Pipeline Type Reference

| Pipeline                     | Batching Type | Grouping Key | Expected JSON Root Key |
|------------------------------|---------------|--------------|------------------------|
| LoadPipeline                 | Per-ID        | LoadID       | LoadPipeline           |
| MultiDestinationRake         | Per-ID        | LoadID       | MultiDestinationRake   |
| LoadAttribute                | Global        | N/A          | LoadAttribute          |
| MaintenanceBlock             | Global        | N/A          | MaintenanceBlock       |
| MaintenanceBlockResource     | Global        | N/A          | MaintenanceBlockResource|
| TrainServiceUpdateActual     | Global        | N/A          | TrainActual            |

---

## Aggregation Service – Detailed Design & Implementation

### Overview

The Aggregation Service is a Java-based microservice designed to process and dispatch large-scale JSON messages across six ingestion pipelines, handling batching, archiving, throttling, and reporting using Spring Boot and scheduled tasks.

### Architecture Components
- **Controllers:** REST controllers per ingestion type, each on dedicated ports.
- **AggregatorService:** Handles batching, archiving, dispatching, and reporting.
- **ArchiveZipper:** Daily ZIP compression and retention of archives.
- **external-config.properties:** Configures ports, limits, paths, cron jobs, and feature toggles.

### Data Flow
1. Client sends JSON payload to controller.
2. Controller forwards to `AggregatorService`.
3. Archives JSON and batches.
4. Flush based on triggers.
5. Dispatch via non-blocking `WebClient`.
6. Log results and handle failures.
7. Nightly ZIP and retention.

### Controller Responsibilities
- Unique ports and endpoints per configuration.
- LoadPipeline and MDR use per-ID batching; others use global batching.

### AggregatorService Features
- Per-ID vs. Global batching.
- Size and time triggers.
- Archiving incoming/merged JSON.
- Dispatch to external REST URLs.
- DLQ and CSV logging.

### Archiving & Scheduling
- Incoming, merged, and DLQ archives.
- Daily ZIP and retention management.

### Configuration Properties
- Ports, batching triggers, throttling, paths, dispatch URLs, DLQ locations, cron jobs.

### Technologies
- Java 17+, Spring Boot, Jackson, WebClient, Tomcat, ExecutorService.

### Extensibility
- Guide provided to add new pipelines.

---

## Aggregation Service – Feature Guide
- Multi-pipeline support.
- Per-ID and global batching strategies.
- Configurable flushing triggers (size/time).
- Automated archiving and dispatch.
- Throttling, DLQ management, CSV reporting, and scheduled ZIP archiving.
- Configuration-driven, fault-tolerant, observable, and extensible.

---

## Aggregation Service – Troubleshooting, Testing & Deployment

### Troubleshooting
- Guidance on common startup, processing, flushing, dispatch, and archiving issues.

### Testing
- Includes unit, integration, and manual testing strategies.

### Deployment
- Java 17+ with Spring Boot on Linux.
- Filesystem setup with writable archives, reports, and DLQs.
- Configurable ports, URLs, and startup checks.

### Useful Commands
```bash
netstat -anp | grep 700
curl -X POST http://localhost:7001/receive -H "Content-Type: application/json" -d @sample-loadpipeline.json
tail -f app.log
java -cp app.jar com.example.aggregation.ArchiveZipperTest
```

---

## Summary
A robust Java microservice for high-volume JSON ingestion, configurable and extendable for enterprise-scale operations.
