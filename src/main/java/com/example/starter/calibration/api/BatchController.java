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

import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchEventResponse;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchReleaseSnapshotResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.BatchReleaseService;
import com.example.starter.calibration.service.BatchService;
import com.example.starter.calibration.service.ReadingService;
import com.example.starter.calibration.service.ReviewService;

/**
 * 测量批次接口：批次创建、读数提交、离群复核、放行、后续修订与历史诊断查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batches;
    private final ReadingService readings;
    private final ReviewService reviews;
    private final BatchReleaseService batchReleases;

    public BatchController(BatchService batches, ReadingService readings,
                           ReviewService reviews, BatchReleaseService batchReleases) {
        this.batches = batches;
        this.readings = readings;
        this.reviews = reviews;
        this.batchReleases = batchReleases;
    }

    /**
     * 创建批次：201；参数非法 400；批次键重复 409。
     */
    @PostMapping
    public ResponseEntity<BatchResponse> create(@RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.create(request));
    }

    /**
     * 批次明细（含离群与复核）：200；不存在 404。
     */
    @GetMapping("/{batchKey}")
    public BatchDetailResponse detail(@PathVariable String batchKey) {
        return batches.detail(batchKey);
    }

    /**
     * 提交读数：201；参数非法 400；批次已放行 409；无匹配有效证书 422。
     */
    @PostMapping("/{batchKey}/readings")
    public ResponseEntity<ReadingResponse> submitReading(@PathVariable String batchKey,
                                                         @RequestBody SubmitReadingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(readings.submit(batchKey, request));
    }

    /**
     * 提交复核：200；复核 STALE（版本变更或证书失效）410；参数非法 400；
     * 复核人即提交人 422；重复复核或已裁决 409。
     */
    @PostMapping("/{batchKey}/outliers/{outlierId}/reviews")
    public ResponseEntity<ReviewResponse> review(@PathVariable String batchKey,
                                                 @PathVariable long outlierId,
                                                 @RequestBody SubmitReviewRequest request) {
        ReviewResponse response = reviews.review(batchKey, outlierId, request);
        HttpStatus status = "STALE".equals(response.status()) ? HttpStatus.GONE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 离群明细（含复核）：200；不存在 404。
     */
    @GetMapping("/{batchKey}/outliers/{outlierId}")
    public OutlierResponse outlier(@PathVariable String batchKey, @PathVariable long outlierId) {
        return batches.outlierDetail(batchKey, outlierId);
    }

    /**
     * 放行批次：200；已放行 409；存在未裁决离群或证书已撤销 422。放行人通过 X-Actor-Id 提供。
     */
    @PostMapping("/{batchKey}/release")
    public BatchReleaseResponse release(@PathVariable String batchKey,
                                        @RequestBody ReleaseBatchRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return batchReleases.release(batchKey, request, actor);
    }

    /**
     * 创建后续测量修订：201；批次未放行 409。
     */
    @PostMapping("/{batchKey}/revisions")
    public ResponseEntity<BatchResponse> createRevision(@PathVariable String batchKey,
                                                        @RequestBody CreateRevisionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.createRevision(batchKey, request));
    }

    /**
     * 批次事件历史（诊断）：200；不存在 404。
     */
    @GetMapping("/{batchKey}/events")
    public List<BatchEventResponse> events(@PathVariable String batchKey) {
        return batches.events(batchKey);
    }

    /**
     * 批次放行快照：200；不存在 404。
     */
    @GetMapping("/{batchKey}/releases")
    public List<BatchReleaseSnapshotResponse> releases(@PathVariable String batchKey) {
        return batches.releases(batchKey);
    }
}