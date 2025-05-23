// src/main/java/com/example/aggregation/MessageController.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives LoadPipeline messages on port 9021 → /receive
 */
@RestController
@RequestMapping("/receive")
public class MessageController {

    private final AggregatorService aggregatorService;

    @Autowired
    public MessageController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody String message) {
        aggregatorService.processIncomingMessage(message, "port");
        return ResponseEntity.ok("Message received successfully");
    }
}
