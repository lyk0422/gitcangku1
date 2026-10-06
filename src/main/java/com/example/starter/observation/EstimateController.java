package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分层样方密度估计 API：生成有来源约束的计算成果并查询冻结结果，不生成通用报表。
 */
@RestController
@RequestMapping("/api/estimates")
@Validated
public class EstimateController {

    private final EstimateService estimateService;

    public EstimateController(EstimateService estimateService) {
        this.estimateService = estimateService;
    }

    /**
     * 生成分层密度估计成果：2 至 5 层，每层正整数总体面积与 1 至 5 个不同观测的当前版本。
     */
    @PostMapping
    public ResponseEntity<EstimateResponse> create(@Valid @RequestBody EstimateRequest request) {
        EstimateService.EstimateOutcome outcome = estimateService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按 estimateId 查询冻结成果；后续观测变更不影响已生成成果。
     */
    @GetMapping("/{estimateId}")
    public EstimateResponse getEstimate(@PathVariable String estimateId) {
        return estimateService.getEstimate(estimateId);
    }
}
