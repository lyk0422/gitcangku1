package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchHistoryResponse;
import com.example.starter.calibration.api.dto.CreateOutlierBatchRequest;
import com.example.starter.calibration.api.dto.OutlierBatchResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.IdempotencyService;
import com.example.starter.calibration.service.OutlierBatchService;
import com.example.starter.calibration.service.OutlierReviewService;

/**
 * 校准离群复核接口：批次创建、读数提交（离群检测）、复核（法定人数裁决）、
 * 批次放行门禁，以及明细、历史与诊断查询。写接口均要求 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
public class OutlierController {

    private final OutlierBatchService batches;
    private final OutlierReviewService reviews;

    public OutlierController(OutlierBatchService batches, OutlierReviewService reviews) {
        this.batches = batches;
        this.reviews = reviews;
    }

    /**
     * 创建测量批次：201；参数非法 400；证书不存在 404；证书已撤销或批次键重复 409。
     */
    @PostMapping("/outlier-batches")
    public ResponseEntity<String> createBatch(@RequestBody CreateOutlierBatchRequest request) {
        return respond(batches.createBatch(request));
    }

    /**
     * 批次明细：当前统计实际值；不存在 404。
     */
    @GetMapping("/outlier-batches/{batchKey}")
    public OutlierBatchResponse batchDetail(@PathVariable String batchKey) {
        return batches.detail(batchKey);
    }

    /**
     * 批次历史：统计重算与放行快照；不存在 404。
     */
    @GetMapping("/outlier-batches/{batchKey}/history")
    public BatchHistoryResponse batchHistory(@PathVariable String batchKey) {
        return batches.history(batchKey);
    }

    /**
     * 批次离群记录列表（诊断查询）；批次不存在 404。
     */
    @GetMapping("/outlier-batches/{batchKey}/outliers")
    public List<OutlierResponse> batchOutliers(@PathVariable String batchKey) {
        return reviews.listByBatch(batchKey);
    }

    /**
     * 提交批次读数：201；批次不存在 404；读数键重复或证书已撤销 409。
     * 超差读数自动生成 OUTLIER 记录并阻断批次放行。
     */
    @PostMapping("/outlier-batches/{batchKey}/readings")
    public ResponseEntity<String> submitReading(@PathVariable String batchKey,
                                                @RequestBody SubmitReadingRequest request) {
        return respond(batches.submitReading(batchKey, request));
    }

    /**
     * 放行批次：200；批次不存在 404；重复放行或证书已撤销 409；存在未裁决离群值 422。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/outlier-batches/{batchKey}/release")
    public ResponseEntity<String> releaseBatch(@PathVariable String batchKey,
                                               @RequestBody ReleaseBatchRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return respond(batches.releaseBatch(batchKey, request, actor));
    }

    /**
     * 提交离群复核：201；参数非法 400；离群记录不存在 404；
     * 复核人=提交人、重复复核、已裁决 409；引用版本失效 410（记录 STALE 复核）。
     */
    @PostMapping("/outliers/{outlierId}/reviews")
    public ResponseEntity<String> review(@PathVariable long outlierId,
                                         @RequestBody SubmitReviewRequest request) {
        return respond(reviews.review(outlierId, request));
    }

    /**
     * 离群记录明细（诊断查询）：封存证据与复核列表；不存在 404。
     */
    @GetMapping("/outliers/{outlierId}")
    public OutlierResponse outlierDetail(@PathVariable long outlierId) {
        return reviews.detail(outlierId);
    }

    private static ResponseEntity<String> respond(IdempotencyService.StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
