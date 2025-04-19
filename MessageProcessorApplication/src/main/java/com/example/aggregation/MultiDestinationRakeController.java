package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP ingestion for MultiDestinationRake messages.
 *  • POST /receive-mdr on mdr.server.port
 */
@RestController
@RequestMapping("/receive-mdr")
public class MultiDestinationRakeController {

    private final AggregatorService svc;

    @Autowired
    public MultiDestinationRakeController(AggregatorService svc) {
        this.svc = svc;
    }

    /**
     * Receives raw JSON, tags it as "port", and returns 200.
     */
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        svc.processMdr(json, "port");
        return ResponseEntity.ok("MDR received");
    }
}
