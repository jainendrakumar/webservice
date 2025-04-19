// src/main/java/com/example/aggregation/MaintenanceBlockResourceController.java
package com.example.aggregation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives MaintenanceBlockResource JSON and delegates to AggregatorService.
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
