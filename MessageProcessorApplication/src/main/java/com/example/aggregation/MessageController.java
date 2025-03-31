package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/**
 * Jainendra Kumar
 * ToDo:
 */

@RestController
public class MessageController {

    @Autowired
    private AggregatorService aggregatorService;

    @PostMapping("/receive")
    public ResponseEntity<String> receiveMessage(@RequestBody String message) {
        aggregatorService.processIncomingMessage(message);
        return ResponseEntity.ok("Message received");
    }
}
