package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives LoadPipeline JSON messages at POST /receive
 * on the port defined by loadpipeline.server.port.
 */
@RestController
@RequestMapping("/receive")
public class LoadPipelineController {

    private final AggregatorService aggregatorService;

    @Autowired
    public LoadPipelineController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    /**
     * Accepts the raw JSON payload, tags it as "port",
     * and returns immediately.
     */
    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody String message) {
        aggregatorService.processIncomingMessage(message, "port");
        return ResponseEntity.ok("LoadPipeline message received");
    }
}
