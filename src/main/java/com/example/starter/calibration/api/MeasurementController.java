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

import com.example.starter.calibration.api.dto.BatchSubmitRequest;
import com.example.starter.calibration.api.dto.BatchSubmitResponse;
import com.example.starter.calibration.api.dto.LineageResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、批量提交、批量放行、历史明细、测量血缘、当前可用结果查询。
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
     * 提交测量：201（同键重放 200）；参数非法 400；测量键或引用键冲突 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        MeasurementService.SubmitOutcome outcome = measurements.submit(request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.body());
    }

    /**
     * 批量提交测量（1～50 条，先按最终引用预校验，原子写入）：201（全部为重放 200）；
     * 批次非法 400；任一条目不满足条件整批拒绝 422 并返回各项原因。
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchSubmitResponse> batchSubmit(@RequestBody BatchSubmitRequest request) {
        MeasurementService.BatchSubmitOutcome outcome = measurements.batchSubmit(request);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.body());
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；任一项不满足条件整批拒绝 409 并返回各项原因；
     * singleBatchOnly 证书绑定冲突整批 422 并返回已绑定批次。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 测量血缘：全部版本按版本号升序，current 标记当前有效版本；不存在 404。
     */
    @GetMapping("/{key}/lineage")
    public LineageResponse lineage(@PathVariable String key) {
        return measurements.lineage(key);
    }

    /**
     * 历史明细：原始测量、计算值、显示值与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
