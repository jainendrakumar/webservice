// src/main/java/com/example/aggregation/LoadAttributeController.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for LoadAttribute ingestion.
 *
 * <p>Listens on /receive-loadattribute at the port configured by
 * loadattribute.server.port.</p>
 */
@RestController
@RequestMapping("/receive-loadattribute")
public class LoadAttributeController {

    private final AggregatorService service;

    @Autowired
    public LoadAttributeController(AggregatorService service) {
        this.service = service;
    }

    /**
     * Accepts raw JSON, tags it as "port", and delegates to the service.
     *
     * @param json raw JSON payload containing "LoadAttribute" array
     * @return 200 OK acknowledgement
     */
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        service.processLoadAttribute(json, "port");
        return ResponseEntity.ok("LoadAttribute received");
    }
}
