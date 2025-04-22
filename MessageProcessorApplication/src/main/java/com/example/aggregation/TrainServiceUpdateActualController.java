package com.example.aggregation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for TrainServiceUpdateActual ingestion.
 *
 * <p>Exposes endpoint at: <code>/receive-trainserviceupdate</code> (port from {@code trainserviceupdate.server.port})</p>
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.0.0
 * @since 2025-04-20
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
