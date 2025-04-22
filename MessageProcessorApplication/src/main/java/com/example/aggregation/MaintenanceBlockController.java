package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for MaintenanceBlock ingestion.
 *
 * <p>Exposes endpoint at: <code>/receive-maintenanceblock</code> (port from {@code maintenanceblock.server.port})</p>
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.0.0
 * @since 2025-04-20
 */
@RestController
@RequestMapping("/receive-maintenanceblock")
public class MaintenanceBlockController {

    private final AggregatorService service;

    @Autowired
    public MaintenanceBlockController(AggregatorService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        service.processMaintenanceBlock(json, "port");
        return ResponseEntity.ok("MaintenanceBlock received");
    }
}
