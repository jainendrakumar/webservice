package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for MaintenanceBlockResource ingestion.
 *
 * <p>Exposes endpoint at: <code>/receive-maintenanceblockresource</code> (port from {@code maintenanceblockresource.server.port})</p>
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.0.0
 * @since 2025-04-20
 */
@RestController
@RequestMapping("/receive-maintenanceblockresource")
public class MaintenanceBlockResourceController {

    private final AggregatorService service;

    @Autowired
    public MaintenanceBlockResourceController(AggregatorService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        service.processMaintenanceBlockResource(json, "port");
        return ResponseEntity.ok("MaintenanceBlockResource received");
    }
}
