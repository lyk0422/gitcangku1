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
import com.example.starter.calibration.api.dto.RecalculateRequest;
import com.example.starter.calibration.api.dto.RejectRequest;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticsResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交（含环境补偿）、重算、驳回、批量放行、放行诊断、历史明细、当前可用结果查询。
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
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书或环境超区间 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 重算（仅未放行测量）：一个事务内按新环境生成新测量版本并复位待放行；已放行 409。
     */
    @PostMapping("/recalculate")
    public MeasurementResponse recalculate(@RequestBody RecalculateRequest request) {
        return measurements.recalculate(request);
    }

    /**
     * 驳回未放行测量：记录驳回历史并置 REJECTED；已放行 409、重复驳回 409。
     * 驳回人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{key}/reject")
    public MeasurementResponse reject(@PathVariable String key, @RequestBody RejectRequest request,
                                      @RequestHeader("X-Actor-Id") String actor) {
        return measurements.reject(key, request, actor);
    }

    /**
     * 批量放行（1～50 条，原子）。不带 uncertaintyLimit 时任一条件不符整批 409；
     * 带 uncertaintyLimit 时走补偿门禁，任一测量缺环境/补偿后超规格/不确定度超限整次 422。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor, request.uncertaintyLimit());
    }

    /**
     * 放行诊断：按补偿门禁预评估一批测量，不改变状态；可传 uncertaintyLimit。
     */
    @PostMapping("/release/diagnostics")
    public ReleaseDiagnosticsResponse diagnostics(@RequestBody ReleaseRequest request) {
        return releases.diagnose(request.keys(), request.uncertaintyLimit());
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始值、补偿值、系数版本、重算链、放行与驳回历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
