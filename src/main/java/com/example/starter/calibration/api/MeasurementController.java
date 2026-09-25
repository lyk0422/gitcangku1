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
import com.example.starter.calibration.api.dto.RecalcRequest;
import com.example.starter.calibration.api.dto.RecalcResponse;
import com.example.starter.calibration.api.dto.RejectRequest;
import com.example.starter.calibration.api.dto.RejectResponse;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticItem;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.RecalcService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交（可带环境补偿）、批量放行、批量驳回、重算、放行诊断、历史明细、当前可用结果查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final RecalcService recalcs;

    public MeasurementController(MeasurementService measurements,
                                ReleaseService releases,
                                RecalcService recalcs) {
        this.measurements = measurements;
        this.releases = releases;
        this.recalcs = recalcs;
    }

    /**
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配证书/系数或环境超适用区间 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；
     * 环境补偿门禁不通过（缺环境/补偿后超规格/不确定度超限）整批 422 并稳定列出测量标识；
     * 结构性不满足整批 409。放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request, actor);
    }

    /**
     * 批量驳回（1～50 条，原子）：200；已放行不可驳回、重复驳回或缺失整批 409。
     */
    @PostMapping("/reject")
    public RejectResponse reject(@RequestBody RejectRequest request,
                                  @RequestHeader("X-Actor-Id") String actor) {
        return releases.reject(request, actor);
    }

    /**
     * 重算未放行测量：在一个事务内用当前生效系数版本生成新版本并重新评估整批；已放行 409。
     */
    @PostMapping("/recalc")
    public ResponseEntity<RecalcResponse> recalc(@RequestBody RecalcRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recalcs.recalc(request));
    }

    /**
     * 放行诊断：稳定（字典序）返回各测量的放行判定原因，不改变状态。
     */
    @PostMapping("/diagnostics")
    public List<ReleaseDiagnosticItem> diagnostics(@RequestBody ReleaseRequest request,
                                                    @RequestHeader(value = "X-Actor-Id", required = false)
                                                    String actor) {
        String actorId = actor == null || actor.isBlank() ? "diagnostic-viewer" : actor.trim();
        return releases.diagnose(request == null ? null : request.keys(),
                request == null ? null : request.uncertaintyLimit(), actorId);
    }

    /**
     * 当前可用结果：最新版本已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始值、补偿值、系数版本、重算链与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
