# Message Processor Application - Developer Guide

## Introduction

This guide provides instructions for developers to build, configure, and run the Message Processor application. The application is a Spring Boot service designed to ingest, aggregate, and dispatch JSON messages from various pipelines.

## Prerequisites

Before you begin, ensure you have the following installed on your development machine:

1.  **Java Development Kit (JDK)**: Version 11 or later. You can verify your installation by running `java -version` in your terminal.
2.  **Apache Maven**: Version 3.6 or later. Maven is used for building the project and managing dependencies. Verify your installation with `mvn -version`.
3.  **Git** (Optional): For cloning the repository if the source code is managed in a Git repository.

## Getting the Code

If the code is in a Git repository, clone it:

```bash
git clone <repository-url>
cd message-processor
```

Otherwise, ensure you have the project source code directory containing the `pom.xml` file and the `src` folder.

## Configuration

The application relies heavily on an external configuration file named `external-config.properties`. This file **must** be present in the same directory where the application JAR file is run, or its location must be specified via Spring Boot's configuration mechanisms.

Key properties to configure in `external-config.properties` include:

*   **Server Ports**: `loadpipeline.server.port`, `mdr.server.port`, etc. (Define the listening ports for each ingestion controller).
*   **Aggregation Settings**: `consolidation.<pipeline>.timeframe`, `bucket.<pipeline>.flush.size` (Control batching behavior).
*   **Archiving**: `archiving.<pipeline>.enabled`, `archive.<pipeline>.incoming.root`, `archive.<pipeline>.merged.root` (Configure message archiving).
*   **Target Endpoints**: `target.<pipeline>.rest.url`, `target.<pipeline>.rest.enabled` (Define downstream REST service URLs).
*   **Throttling**: `throttling.<pipeline>.enabled`, `throttling.<pipeline>.limit` (Configure requests-per-second limits).
*   **Dead Letter Queue**: `deadletterqueue.<pipeline>` (Specify paths for failed message archiving).
*   **Reporting**: `report.<pipeline>.prefix` (Define prefixes for daily CSV report files).

Ensure all necessary directories specified in the configuration (e.g., for archiving, DLQ, reports) exist and have the required write permissions for the user running the application.

## Building the Application

Use Maven to compile the code and package it into an executable JAR file.

1.  Navigate to the root directory of the project (where `pom.xml` is located).
2.  Run the following Maven command:

    ```bash
    mvn clean package
    ```

    *   `clean`: Removes any previous build artifacts (e.g., the `target` directory).
    *   `package`: Compiles the source code, runs tests (if any), and packages the application into an executable JAR file in the `target/` directory. The JAR file will typically be named `message-processor-1.0.0.jar` (version might vary).

Upon successful execution, you will find the JAR file in the `target/` subdirectory.

## Running the Application

1.  Copy the generated JAR file (e.g., `target/message-processor-1.0.0.jar`) to your desired deployment directory.
2.  Ensure the `external-config.properties` file is present in the **same directory** as the JAR file.
3.  Run the application using the `java -jar` command:

    ```bash
    java -jar message-processor-1.0.0.jar
    ```

    *(Replace `message-processor-1.0.0.jar` with the actual name of your JAR file)*

The application will start, and Spring Boot will log startup information to the console. The service will begin listening on the configured ports for incoming messages.

### Running with Specific Configuration Location

If `external-config.properties` is not in the same directory as the JAR, you can specify its location using the `spring.config.location` property:

```bash
java -jar message-processor-1.0.0.jar --spring.config.location=file:/path/to/your/external-config.properties
```

## Development Environment (IDE)

For development, you can import the project into your favorite IDE (e.g., IntelliJ IDEA, Eclipse, VS Code) as a Maven project. You can typically run the application directly from the IDE by running the `main` method in the `AggregationServiceApplication` class. Ensure your IDE is configured to use JDK 11.

When running from the IDE, you might need to configure how `external-config.properties` is loaded. Common ways include:

*   Placing `external-config.properties` in the `src/main/resources` directory (though the `pom.xml` specifies `<spring.config.name>external-config`, so it will look for `external-config.properties` instead of `application.properties`).
*   Configuring the run/debug configuration in your IDE to specify the config file location via VM options (`-Dspring.config.location=...`) or program arguments (`--spring.config.location=...`).

## Stopping the Application

To stop the application running in the foreground, press `Ctrl+C` in the terminal where it was launched. If running as a background service, use the appropriate system commands (e.g., `kill <pid>`, `systemctl stop <service-name>`).
