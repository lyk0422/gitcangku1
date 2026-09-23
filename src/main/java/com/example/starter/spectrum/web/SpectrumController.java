package com.example.starter.spectrum.web;

import com.example.starter.spectrum.dto.CreateNetworkRequest;
import com.example.starter.spectrum.dto.NetworkStateView;
import com.example.starter.spectrum.dto.PlanResultView;
import com.example.starter.spectrum.dto.PlanSubmitRequest;
import com.example.starter.spectrum.service.SpectrumService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 试验台站频率协同 API。所有写操作要求 X-Request-Id 头（全局唯一）。
 */
@RestController
@RequestMapping("/api/spectrum/networks")
public class SpectrumController {

    private final SpectrumService service;

    public SpectrumController(SpectrumService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<NetworkStateView> createNetwork(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody CreateNetworkRequest request) {
        SpectrumService.OperationResult<NetworkStateView> result =
                service.createNetwork(request, requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{networkId}")
    public NetworkStateView getNetwork(@PathVariable String networkId) {
        return service.getNetwork(networkId);
    }

    @PostMapping("/{networkId}/plans")
    public ResponseEntity<PlanResultView> submitPlan(
            @PathVariable String networkId,
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody PlanSubmitRequest request) {
        SpectrumService.OperationResult<PlanResultView> result =
                service.submitPlan(networkId, request, requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{networkId}/plans")
    public List<PlanResultView> listPlans(@PathVariable String networkId) {
        return service.listPlans(networkId);
    }
}
