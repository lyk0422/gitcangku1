package com.example.starter.web;

import com.example.starter.domain.Dispute;
import com.example.starter.service.DisputeService;
import com.example.starter.service.OpResult;
import com.example.starter.service.QueryService;
import com.example.starter.web.dto.Requests.AdjudicateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 争议裁决入口。 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeService disputeService;
    private final QueryService queryService;

    public DisputeController(DisputeService disputeService, QueryService queryService) {
        this.disputeService = disputeService;
        this.queryService = queryService;
    }

    @PostMapping("/{id}/adjudicate")
    public ResponseEntity<Dispute> adjudicate(@PathVariable long id, @Valid @RequestBody AdjudicateRequest req) {
        OpResult<Dispute> result = disputeService.adjudicate(id, req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/{id}")
    public Dispute detail(@PathVariable long id) {
        return queryService.dispute(id);
    }
}