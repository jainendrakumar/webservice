package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP ingestion for LoadPipeline messages.
 *  • POST /receive on loadpipeline.server.port
 */
@RestController
@RequestMapping("/receive")
public class LoadPipelineController {

    private final AggregatorService svc;

    @Autowired
    public LoadPipelineController(AggregatorService svc) {
        this.svc = svc;
    }

    /**
     * Receives raw JSON, tags it as "port", and returns 200.
     */
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        svc.processLoadPipeline(json, "port");
        return ResponseEntity.ok("LoadPipeline received");
    }
}
