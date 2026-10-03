package com.example.starter.stratum;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.stratum.StratumDtos.RegisterRequest;
import com.example.starter.stratum.StratumDtos.StratumResponse;

/**
 * 层位登记入口。
 */
@RestController
@RequestMapping("/api/strata")
public class StratumController {

    private final StratumService service;

    public StratumController(StratumService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StratumResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(service.register(req));
    }
}