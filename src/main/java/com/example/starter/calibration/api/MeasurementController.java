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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.IdempotencyService;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、批量放行、历史明细、当前可用结果查询。
 * 写入支持 requestId 幂等：同键同参重放首次响应，异参 409，失败不占键。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final IdempotencyService idempotency;

    public MeasurementController(MeasurementService measurements, ReleaseService releases,
                                 IdempotencyService idempotency) {
        this.measurements = measurements;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书或读数越出曲线节点区间 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        MeasurementResponse body = idempotency.execute(request.requestId(), "MEASUREMENT_SUBMIT",
                request.submittedBy() == null ? "" : request.submittedBy().trim(),
                "measurement:" + request.measurementKey(), request, request.expectedVersion(),
                () -> measurements.submit(request), MeasurementResponse.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；任一项不满足条件整批拒绝 409 并返回各项原因。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return idempotency.execute(request.requestId(), "MEASUREMENT_RELEASE", actor.trim(),
                "release", request, request.expectedVersion(),
                () -> releases.release(request.keys(), actor), ReleaseResponse.class);
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始测量、计算值、显示值与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
