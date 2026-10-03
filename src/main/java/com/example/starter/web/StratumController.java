package com.example.starter.web;

import com.example.starter.service.StratumService;
import com.example.starter.web.dto.Requests.RegisterStratumRequest;
import com.example.starter.web.dto.Responses.StratumResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遗址层位登记入口。
 */
@RestController
@RequestMapping("/api/strata")
public class StratumController {

    private final StratumService stratumService;

    public StratumController(StratumService stratumService) {
        this.stratumService = stratumService;
    }

    /**
     * 登记层位，返回 201；遗址编号+层位号重复返回 409。
     */
    @PostMapping
    public ResponseEntity<StratumResponse> register(@Valid @RequestBody RegisterStratumRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stratumService.register(req));
    }
}