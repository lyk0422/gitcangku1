package com.example.starter.calibration.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchReleaseRequest;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviewOutlierRequest;
import com.example.starter.calibration.api.dto.RevisionResponse;
import com.example.starter.calibration.api.dto.SubmitBatchReadingRequest;
import com.example.starter.calibration.api.dto.ReviseBatchRequest;
import com.example.starter.calibration.service.BatchService;
import com.example.starter.calibration.service.IdempotencyService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 测量批次与离群复核接口。
 * 所有变更接口支持 X-Request-Id 幂等：同参重放首次结果，异参 409，业务失败不占用 requestId。
 */
@RestController
@RequestMapping("/api")
public class BatchController {

    private final BatchService batches;
    private final IdempotencyService idempotency;

    public BatchController(BatchService batches, IdempotencyService idempotency) {
        this.batches = batches;
        this.idempotency = idempotency;
    }

    /** 创建测量批次及首版修订：201；批次键重复 409。 */
    @PostMapping("/batches")
    public ResponseEntity<Object> create(@RequestBody CreateBatchRequest request,
                                         HttpServletRequest http) {
        return run(http, request, () -> batches.createBatch(request));
    }

    /** 向当前修订提交读数：201；读数离群时同事务自动生成 OUTLIER 记录并阻断后续放行。 */
    @PostMapping("/batches/{batchKey}/readings")
    public ResponseEntity<Object> submitReading(@PathVariable String batchKey,
                                                @RequestBody SubmitBatchReadingRequest request,
                                                HttpServletRequest http) {
        return run(http, request, () -> {
            MeasurementResponse body = batches.submitReading(batchKey, request);
            return new BatchService.ActionOutcome(201, body);
        });
    }

    /** 创建后续测量修订：201；当前修订未放行 409。 */
    @PostMapping("/batches/{batchKey}/revisions")
    public ResponseEntity<Object> revise(@PathVariable String batchKey,
                                         @RequestBody ReviseBatchRequest request,
                                         HttpServletRequest http) {
        return run(http, request, () -> {
            RevisionResponse body = batches.revise(batchKey, request);
            return new BatchService.ActionOutcome(201, body);
        });
    }

    /**
     * 放行批次当前修订：200；仍有未裁决离群（含待仲裁）422；证书撤销/放行人冲突 422；重复放行 409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/batches/{batchKey}/release")
    public ResponseEntity<Object> release(@PathVariable String batchKey,
                                          @RequestBody(required = false) BatchReleaseRequest request,
                                          @RequestHeader("X-Actor-Id") String actor,
                                          HttpServletRequest http) {
        return run(http, request == null ? new BatchReleaseRequest(null) : request, () -> {
            BatchReleaseResponse body = batches.release(batchKey, actor,
                    request == null ? null : request.measurementKeys());
            return new BatchService.ActionOutcome(200, body);
        });
    }

    /** 批次明细/历史/诊断：只读，不改变状态；不存在 404。 */
    @GetMapping("/batches/{batchKey}")
    public BatchDetailResponse detail(@PathVariable String batchKey) {
        return batches.detail(batchKey);
    }

    /**
     * 提交离群复核：200 且成裁时更新裁决；引用版本已变更或证书失效时 410 且复核标记 STALE；
     * 复核人=提交人或重复复核人 409；离群已裁决 409。放行人/复核人通过 X-Actor-Id 提供。
     */
    @PostMapping("/outliers/{outlierKey}/reviews")
    public ResponseEntity<Object> review(@PathVariable String outlierKey,
                                         @RequestBody ReviewOutlierRequest request,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         HttpServletRequest http) {
        return run(http, request, () -> batches.review(outlierKey, actor, request));
    }

    /** 离群明细（含全部复核历史），只读；不存在 404。 */
    @GetMapping("/outliers/{outlierKey}")
    public OutlierResponse outlierDetail(@PathVariable String outlierKey) {
        return batches.outlierDetail(outlierKey);
    }

    private ResponseEntity<Object> run(HttpServletRequest http, Object requestBody,
                                       java.util.function.Supplier<BatchService.ActionOutcome> action) {
        IdempotencyService.Context ctx = context(http, requestBody);
        IdempotencyService.Response response = idempotency.execute(ctx,
                () -> {
                    BatchService.ActionOutcome outcome = action.get();
                    return new IdempotencyService.Outcome(outcome.statusCode(), outcome.body());
                });
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }

    private IdempotencyService.Context context(HttpServletRequest http, Object requestBody) {
        String requestId = http.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String actor = http.getHeader("X-Actor-Id");
        String path = http.getRequestURI()
                + (http.getQueryString() == null ? "" : "?" + http.getQueryString());
        return new IdempotencyService.Context(
                requestId.trim(), http.getMethod(), path, actor, idempotency.fingerprint(requestBody));
    }
}
