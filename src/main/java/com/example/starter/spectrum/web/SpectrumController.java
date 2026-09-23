package com.example.starter.spectrum.web;

import com.example.starter.spectrum.dto.CreateNetworkRequest;
import com.example.starter.spectrum.dto.NetworkStateResponse;
import com.example.starter.spectrum.dto.PlanHistoryResponse;
import com.example.starter.spectrum.dto.PlanResultResponse;
import com.example.starter.spectrum.dto.SubmitPlanRequest;
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

/**
 * 合成台站频率协同 API：网络状态、方案提交、不可变历史。
 * 所有写操作必须携带 X-Request-Id 请求头。
 */
@RestController
@RequestMapping("/api/spectrum/networks")
public class SpectrumController {

    private final SpectrumService spectrumService;

    public SpectrumController(SpectrumService spectrumService) {
        this.spectrumService = spectrumService;
    }

    /** 创建网络：一次定义台站、预算与有向干扰边。 */
    @PostMapping
    public ResponseEntity<NetworkStateResponse> createNetwork(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody CreateNetworkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(spectrumService.createNetwork(requestId, request));
    }

    /** 查询网络当前状态。 */
    @GetMapping("/{networkId}")
    public NetworkStateResponse getNetwork(@PathVariable String networkId) {
        return spectrumService.getNetwork(networkId);
    }

    /** 提交频率方案。 */
    @PostMapping("/{networkId}/plans")
    public PlanResultResponse submitPlan(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String networkId,
            @Valid @RequestBody SubmitPlanRequest request) {
        return spectrumService.submitPlan(requestId, networkId, request);
    }

    /** 查询不可变方案历史，按版本升序。 */
    @GetMapping("/{networkId}/plans")
    public PlanHistoryResponse getHistory(@PathVariable String networkId) {
        return spectrumService.getHistory(networkId);
    }
}
