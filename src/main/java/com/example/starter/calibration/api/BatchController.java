package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.DiagnosticsResponse;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.service.BatchService;

/**
 * 测量批次接口：创建（目标值+允许偏差）、读数提交（离群检测）、放行（离群阻断）、明细与诊断。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batches;

    public BatchController(BatchService batches) {
        this.batches = batches;
    }

    /**
     * 创建批次：201；参数非法 400；批次键重复或幂等异参 409。
     */
    @PostMapping
    public ResponseEntity<BatchResponse> create(@RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.createBatch(request));
    }

    /**
     * 批次明细：200；不存在 404。读取不改变状态。
     */
    @GetMapping("/{key}")
    public BatchResponse detail(@PathVariable String key) {
        return batches.detail(key);
    }

    /**
     * 批次诊断：明细、离群与复核历史、统计重算历史、放行快照；不存在 404。读取不改变状态。
     */
    @GetMapping("/{key}/diagnostics")
    public DiagnosticsResponse diagnostics(@PathVariable String key) {
        return batches.diagnostics(key);
    }

    /**
     * 提交读数：201；参数非法 400；批次不存在 404；读数键重复或幂等异参 409；无匹配有效证书 422。
     * 读数超出允许偏差时自动生成 PENDING 离群记录并阻断放行。
     */
    @PostMapping("/{key}/readings")
    public ResponseEntity<ReadingResponse> submitReading(@PathVariable String key,
                                                         @RequestBody SubmitReadingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.submitReading(key, request));
    }

    /**
     * 批次放行：200；批次不存在 404；重复放行 409；存在未裁决离群值 422（返回实际与要求数量）。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{key}/release")
    public BatchReleaseResponse release(@PathVariable String key,
                                        @RequestBody(required = false) ReleaseBatchRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        String requestId = request == null ? null : request.requestId();
        return batches.release(key, actor, requestId);
    }
}