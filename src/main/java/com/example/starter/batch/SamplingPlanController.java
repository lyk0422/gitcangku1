package com.example.starter.batch;

import com.example.starter.batch.dto.CreatePlanRequest;
import com.example.starter.batch.dto.PlanDetailResponse;
import com.example.starter.batch.dto.RegisterSampleRequest;
import com.example.starter.batch.dto.SampleRecordResponse;
import com.example.starter.batch.dto.SamplingPlanResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 抽样检验计划接口：计划创建、逐件登记、计划明细/逐件结果/判定历史查询。
 */
@RestController
public class SamplingPlanController {

    private final SamplingPlanService service;

    public SamplingPlanController(SamplingPlanService service) {
        this.service = service;
    }

    /**
     * 为隔离中的批次创建 OPEN 抽样检验计划。
     */
    @PostMapping("/api/batches/{batchKey}/sampling-plans")
    public ResponseEntity<String> createPlan(@PathVariable String batchKey,
                                             @Valid @RequestBody CreatePlanRequest request) {
        return stored(service.createPlan(batchKey, request));
    }

    /**
     * 逐件登记样本结果并在同事务内判定。
     */
    @PostMapping("/api/sampling-plans/{planKey}/samples")
    public ResponseEntity<String> registerSample(@PathVariable String planKey,
                                                 @Valid @RequestBody RegisterSampleRequest request) {
        return stored(service.registerSample(planKey, request));
    }

    /**
     * 计划明细：计划概要 + 全部逐件结果。
     */
    @GetMapping("/api/sampling-plans/{planKey}")
    public PlanDetailResponse planDetail(@PathVariable String planKey) {
        return service.planDetail(planKey);
    }

    /**
     * 计划逐件结果列表（按样本序号升序）。
     */
    @GetMapping("/api/sampling-plans/{planKey}/samples")
    public List<SampleRecordResponse> planSamples(@PathVariable String planKey) {
        return service.planSamples(planKey);
    }

    /**
     * 批次的计划与判定历史（按计划序号升序）。
     */
    @GetMapping("/api/batches/{batchKey}/sampling-plans")
    public List<SamplingPlanResponse> batchPlans(@PathVariable String batchKey) {
        return service.batchPlans(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
