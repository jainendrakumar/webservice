// src/main/java/com/example/aggregation/LoadPipelineController.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for LoadPipeline ingestion.
 *
 * <p>Listens on /receive at the port configured by
 * loadpipeline.server.port.</p>
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.0.0
 * @since 2025-04-20
 */
@RestController
@RequestMapping("/receive")
public class LoadPipelineController {

    private final AggregatorService service;

    @Autowired
    public LoadPipelineController(AggregatorService service) {
        this.service = service;
    }

    /**
     * Accepts raw JSON, tags it as "port", and delegates to the service.
     *
     * @param json raw JSON payload containing "LoadPipeline" array
     * @return 200 OK acknowledgement
     */
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        service.processLoadPipeline(json, "port");
        return ResponseEntity.ok("LoadPipeline received");
    }
}
