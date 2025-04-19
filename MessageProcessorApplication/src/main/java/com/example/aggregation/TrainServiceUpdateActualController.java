// src/main/java/com/example/aggregation/TrainServiceUpdateActualController.java
package com.example.aggregation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives TrainActual JSON and delegates to AggregatorService.
 */
@RestController
@RequestMapping("/receive-trainserviceupdate")
public class TrainServiceUpdateActualController {
    private final AggregatorService service;
    @Autowired
    public TrainServiceUpdateActualController(AggregatorService service) {
        this.service = service;
    }
    @PostMapping
    public ResponseEntity<String> receive(@RequestBody String json) {
        service.processTrainServiceUpdate(json, "port");
        return ResponseEntity.ok("TrainServiceUpdateActual received");
    }
}
