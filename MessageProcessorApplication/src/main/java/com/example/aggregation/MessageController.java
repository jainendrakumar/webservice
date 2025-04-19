package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for receiving incoming messages via HTTP.
 * <p>
 * Exposes an endpoint to accept JSON payloads representing LoadPipeline messages,
 * and delegates processing to the AggregatorService. By tagging the source as "port",
 * incoming messages from this controller are prioritized according to configuration.
 * </p>
 *
 * @author jkr3
 */
@RestController
@RequestMapping("/receive")
public class MessageController {

    private final AggregatorService aggregatorService;

    /**
     * Constructs the controller with the required AggregatorService dependency.
     *
     * @param aggregatorService the service handling message aggregation and dispatch
     */
    @Autowired
    public MessageController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    /**
     * POST endpoint to receive a raw JSON message.
     * <p>
     * This endpoint accepts the message body as a String, tags it with source "port",
     * and invokes the aggregatorService for processing.
     * </p>
     *
     * @param message the raw JSON payload containing a LoadPipeline array
     * @return HTTP 200 OK with acknowledgement text
     */
    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody String message) {
        // Delegate processing to service, marking source as "port"
        aggregatorService.processIncomingMessage(message, "port");
        return ResponseEntity.ok("Message received successfully");
    }
}