// src/main/java/com/example/aggregation/MultiDestinationRakeController.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives MultiDestinationRake messages on port 9022 → /receive-mdr
 */
@RestController
@RequestMapping("/receive-mdr")
public class MultiDestinationRakeController {

    private final AggregatorService aggregatorService;

    @Autowired
    public MultiDestinationRakeController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody String message) {
        aggregatorService.processIncomingMdrMessage(message, "port");
        return ResponseEntity.ok("MultiDestinationRake message received successfully");
    }
}
