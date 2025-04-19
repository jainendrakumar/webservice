// src/main/java/com/example/aggregation/MaintenanceBlockController.java
package com.example.aggregation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives MaintenanceBlock JSON and delegates to AggregatorService.
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
