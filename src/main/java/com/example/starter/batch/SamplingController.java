package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSamplingPlanRequest;
import com.example.starter.batch.dto.RecordSampleRequest;
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
 * 抽样检验计划接口：计划创建、逐件登记，以及计划明细、逐件结果与判定历史查询。
 */
@RestController
public class SamplingController {

    private final SamplingService service;

    public SamplingController(SamplingService service) {
        this.service = service;
    }

    /**
     * 为隔离中的批次创建 OPEN 抽样检验计划。
     */
    @PostMapping("/api/batches/{batchKey}/sampling-plans")
    public ResponseEntity<String> createPlan(@PathVariable String batchKey,
                                             @Valid @RequestBody CreateSamplingPlanRequest request) {
        return stored(service.createPlan(batchKey, request));
    }

    /**
     * 某批次全部计划的判定历史（按创建顺序）。
     */
    @GetMapping("/api/batches/{batchKey}/sampling-plans")
    public List<SamplingPlanResponse> listPlans(@PathVariable String batchKey) {
        return service.listPlans(batchKey);
    }

    /**
     * 计划明细：参数、累计加权缺陷数、已登记件数、状态与判定时刻。
     */
    @GetMapping("/api/sampling-plans/{planKey}")
    public SamplingPlanResponse getPlan(@PathVariable String planKey) {
        return service.getPlan(planKey);
    }

    /**
     * 逐件登记样本结果并在同一事务内完成判定。
     */
    @PostMapping("/api/sampling-plans/{planKey}/samples")
    public ResponseEntity<String> recordSample(@PathVariable String planKey,
                                               @Valid @RequestBody RecordSampleRequest request) {
        return stored(service.recordSample(planKey, request));
    }

    /**
     * 某计划全部逐件登记结果（按样本序号）。
     */
    @GetMapping("/api/sampling-plans/{planKey}/samples")
    public List<SampleRecordResponse> listSamples(@PathVariable String planKey) {
        return service.listSamples(planKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
