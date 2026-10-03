package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.AuditView;
import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.service.BatchReleaseService;
import com.example.starter.calibration.service.BatchService;

/**
 * 测量批次接口：创建批次、提交读数（自动离群检测）、放行、明细与审计查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batches;
    private final BatchReleaseService releases;

    public BatchController(BatchService batches, BatchReleaseService releases) {
        this.batches = batches;
        this.releases = releases;
    }

    /**
     * 创建批次：201；参数非法 400；批次键重复 409；requestId 异参 409。
     */
    @PostMapping
    public ResponseEntity<BatchResponse> create(@RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.createBatch(request));
    }

    /**
     * 提交读数：201；参数非法 400；批次不存在 404；读数键重复 409；无匹配有效证书 422。
     * 读数超出允许偏差时自动生成 OUTLIER 记录并阻断放行。
     */
    @PostMapping("/{batchKey}/readings")
    public ResponseEntity<ReadingResponse> submitReading(@PathVariable String batchKey,
                                                         @RequestBody SubmitReadingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.submitReading(batchKey, request));
    }

    /**
     * 放行批次当前修订：200；批次不存在 404；已放行 409；存在未裁决离群 422（含实际/要求/差额）。
     */
    @PostMapping("/{batchKey}/release")
    public BatchReleaseResponse release(@PathVariable String batchKey,
                                        @RequestBody ReleaseBatchRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(batchKey, actor, request.requestId());
    }

    /**
     * 批次明细：批次、最新统计、读数、离群（含复核历史）、统计历史与放行快照；不存在 404。只读。
     */
    @GetMapping("/{batchKey}")
    public BatchDetailResponse detail(@PathVariable String batchKey) {
        return batches.detail(batchKey);
    }

    /**
     * 批次审计历史（不可变记录，按发生顺序）；不存在 404。只读。
     */
    @GetMapping("/{batchKey}/audits")
    public List<AuditView> audits(@PathVariable String batchKey) {
        return batches.audits(batchKey);
    }
}