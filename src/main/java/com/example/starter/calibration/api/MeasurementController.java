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

import com.example.starter.calibration.api.dto.BatchSubmitMeasurementRequest;
import com.example.starter.calibration.api.dto.BatchSubmitMeasurementResponse;
import com.example.starter.calibration.api.dto.MeasurementLineageResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.RecalculateRequest;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：单条/批量提交、替换标准器重算、批量放行与诊断、血缘明细、当前可用结果查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;

    public MeasurementController(MeasurementService measurements, ReleaseService releases) {
        this.measurements = measurements;
        this.releases = releases;
    }

    /**
     * 提交单条测量：201；参数非法 400；测量键重复 409；
     * 引用证书不存在/已撤销/已到期/未生效/无匹配 422（错误码可区分）。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 批量提交测量：先按最终引用预校验，全部通过后整批原子写入；201；
     * 同 batchId 重放返回首次结果（replayed=true），同键不同载荷 409，任一条引用失败整批 422 不落库。
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchSubmitMeasurementResponse> submitBatch(
            @RequestBody BatchSubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submitBatch(request));
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；常规门禁失败整批 409；
     * singleBatchOnly 跨批次绑定冲突整批 422 并返回已绑定批次。放行人通过 X-Actor-Id 提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 放行诊断：按正式放行相同门禁逐项返回原因，但不绑定、不放行、不改变状态。
     * X-Actor-Id 可选；提供时额外诊断 SAME_ACTOR。
     */
    @PostMapping("/release/diagnose")
    public ReleaseDiagnosticResponse diagnose(@RequestBody ReleaseRequest request,
                                              @RequestHeader(value = "X-Actor-Id", required = false)
                                              String actor) {
        return releases.diagnose(request.keys(), actor);
    }

    /**
     * 对未放行测量替换标准器：生成新测量版本并以新证书重算全部补偿与不确定度，200；
     * 已放行 409；新引用不存在/已撤销/已到期 422；任一步失败整笔回滚，旧版本仍为当前版本。
     */
    @PostMapping("/{key}/recalculate")
    public MeasurementResponse recalculate(@PathVariable String key,
                                           @RequestBody RecalculateRequest request) {
        return measurements.recalculate(key, request);
    }

    /**
     * 测量血缘：当前明细 + 全部版本快照（证书版本、补偿系数、不确定度版本、referenceKey）。
     */
    @GetMapping("/{key}/lineage")
    public MeasurementLineageResponse lineage(@PathVariable String key) {
        return measurements.lineage(key);
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：当前版本原始测量、计算值、不确定度、指纹与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
