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
 * 分层样方密度估计成果 API：生成有来源约束的计算成果并查询冻结结果，不生成通用报表。
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
     * 生成分层密度估计成果：核对全部源观测版本后冻结层、成员、版本与精确结果。
     */
    @PostMapping
    public ResponseEntity<EstimateResponse> create(@Valid @RequestBody CreateEstimateRequest request) {
        EstimateService.EstimateOutcome outcome = estimateService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按成果标识查询冻结的完整成果；后续观测变更不影响已冻结内容。
     */
    @GetMapping("/{estimateId}")
    public EstimateResponse get(@PathVariable String estimateId) {
        return estimateService.get(estimateId);
    }
}
