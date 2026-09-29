package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementVersionResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.ReleaseSnapshotResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.api.dto.UpdateBudgetRequest;
import com.example.starter.calibration.service.BudgetService;
import com.example.starter.calibration.service.IdempotencyService;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、修订、不确定度预算、批量放行、版本/快照历史、当前可用结果查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private static final String OP_REVISE = "REVISE_MEASUREMENT";
    private static final String OP_BUDGET = "UPDATE_BUDGET";
    private static final String OP_RELEASE = "RELEASE_BUDGET";

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final BudgetService budgets;
    private final IdempotencyService idempotency;

    public MeasurementController(MeasurementService measurements,
                                 ReleaseService releases,
                                 BudgetService budgets,
                                 IdempotencyService idempotency) {
        this.measurements = measurements;
        this.releases = releases;
        this.budgets = budgets;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 修订测量（新版本）：200；版本过期 410；无匹配证书 422。
     * 带 X-Request-Id 时按同参重放/异参 409/失败不占键裁决。
     */
    @PostMapping("/revise")
    public ResponseEntity<?> revise(@RequestBody ReviseMeasurementRequest request,
                                    @RequestHeader(value = "X-Actor-Id", required = false) String actor,
                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String operator = requireHeader(actor, "X-Actor-Id");
        if (isBlank(requestId)) {
            return ResponseEntity.ok(measurements.revise(request));
        }
        IdempotencyService.Result<MeasurementResponse> result = idempotency.execute(
                requestId.trim(), OP_REVISE, operator, reviseFields(request),
                () -> measurements.revise(request));
        return toResponse(result);
    }

    /**
     * 整体替换不确定度预算：200；分量重名/数值非法 422；测量版本过期 410。
     * 先校验完整后态再在一个事务内提交，任一失败不改变放行状态。
     * 带 X-Request-Id 时支持幂等重放。
     */
    @PostMapping("/{key}/budget")
    public ResponseEntity<?> replaceBudget(@PathVariable String key,
                                           @RequestHeader(value = "X-Actor-Id", required = false) String actor,
                                           @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                           @RequestBody UpdateBudgetRequest request) {
        String operator = requireHeader(actor, "X-Actor-Id");
        UpdateBudgetRequest normalized = normalizeBudgetPath(key, request);
        if (isBlank(requestId)) {
            return ResponseEntity.ok(budgets.replace(normalized, operator));
        }
        IdempotencyService.Result<BudgetResponse> result = idempotency.execute(
                requestId.trim(), OP_BUDGET, operator, BudgetService.fingerprintFields(normalized),
                () -> budgets.replace(normalized, operator));
        return toResponse(result);
    }

    /**
     * 查询当前预算（读操作，不改状态）。
     */
    @GetMapping("/{key}/budget")
    public BudgetResponse getBudget(@PathVariable String key) {
        return budgets.get(key);
    }

    /**
     * 版本历史：按版本号升序；不存在 404。
     */
    @GetMapping("/{key}/versions")
    public List<MeasurementVersionResponse> versions(@PathVariable String key) {
        return measurements.versions(key);
    }

    /**
     * 某测量业务键全部版本的放行快照历史（按放行时间升序，只读）。
     */
    @GetMapping("/{key}/snapshots")
    public List<ReleaseSnapshotResponse> snapshots(@PathVariable String key) {
        return releases.snapshotsByKey(key);
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；任一项不满足条件整批拒绝
     * （条件冲突 409 / 阈值缺失 422 / 版本过期 410）并返回各项原因。
     * 放行人通过 X-Actor-Id 提供；X-Request-Id 提供时支持幂等重放。
     */
    @PostMapping("/release")
    public ResponseEntity<?> release(@RequestBody ReleaseRequest request,
                                     @RequestHeader(value = "X-Actor-Id", required = false) String actor,
                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String releaser = requireHeader(actor, "X-Actor-Id");
        if (isBlank(requestId)) {
            return ResponseEntity.ok(releases.release(request.keys(), releaser));
        }
        IdempotencyService.Result<ReleaseResponse> result = idempotency.execute(
                requestId.trim(), OP_RELEASE, releaser, ReleaseService.fingerprintFields(request.keys()),
                () -> releases.release(request.keys(), releaser));
        return toResponse(result);
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始测量、计算值、显示值、当前预算与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }

    private UpdateBudgetRequest normalizeBudgetPath(String pathKey, UpdateBudgetRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (!isBlank(request.measurementKey()) && !request.measurementKey().trim().equals(pathKey)) {
            throw ApiException.badRequest("路径测量键与请求体 measurementKey 不一致");
        }
        return new UpdateBudgetRequest(pathKey, request.expectedVersion(), request.components());
    }

    private java.util.Map<String, Object> reviseFields(ReviseMeasurementRequest request) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("measurementKey", request.measurementKey() == null ? "" : request.measurementKey().trim());
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("instrumentId", request.instrumentId() == null ? "" : request.instrumentId().trim());
        fields.put("measuredAt", request.measuredAt() == null ? "" : request.measuredAt().trim());
        fields.put("reading", request.reading() == null ? "" : request.reading().trim());
        fields.put("lowerLimit", request.lowerLimit() == null ? "" : request.lowerLimit().trim());
        fields.put("upperLimit", request.upperLimit() == null ? "" : request.upperLimit().trim());
        fields.put("submittedBy", request.submittedBy() == null ? "" : request.submittedBy().trim());
        return fields;
    }

    private <T> ResponseEntity<?> toResponse(IdempotencyService.Result<T> result) {
        if (result.replay()) {
            // 重放原样返回首次完整响应快照，读取不改状态
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.replayJson());
        }
        return ResponseEntity.ok(result.body());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String requireHeader(String value, String header) {
        if (isBlank(value)) {
            throw ApiException.badRequest(header + " 请求头不能为空");
        }
        return value.trim();
    }
}
