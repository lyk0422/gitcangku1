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
import com.example.starter.calibration.api.dto.ReleaseVersionsRequest;
import com.example.starter.calibration.api.dto.ReleaseVersionsResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、修订、版本化批量放行、旧版批量放行、版本历史、历史明细、当前可用结果查询。
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
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 修订测量：200；非法参数 400；不存在 404；权限/版本/幂等冲突 409；无匹配证书 422。
     * 原提交人通过 X-Actor-Id 携带 expectedRevision、requestId 与非空原因。
     */
    @PostMapping("/{key}/revisions")
    public MeasurementResponse revise(@PathVariable String key,
                                      @RequestBody ReviseMeasurementRequest request,
                                      @RequestHeader("X-Actor-Id") String actor) {
        return measurements.revise(key, request, actor);
    }

    /**
     * 版本化批量放行（1～50 项，原子）：200；批次非法 400；任一项不满足条件整批拒绝 409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release-versions")
    public ReleaseVersionsResponse releaseVersions(@RequestBody ReleaseVersionsRequest request,
                                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.releaseVersions(request.items(), actor);
    }

    /**
     * 批量放行旧入口（1～50 条，原子）：从未修订的测量键保持可用；
     * 已有修订的键整批 409 并要求显式版本（REVISION_REQUIRED）。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 当前可用结果：最新版、已放行且证书未撤销，每键至多一条；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 版本历史：该测量键全部修订版本（按修订号升序）；不存在 404。
     */
    @GetMapping("/{key}/revisions")
    public List<MeasurementResponse> history(@PathVariable String key) {
        return measurements.history(key);
    }

    /**
     * 历史明细：默认返回最新版本，含原始测量、计算值、显示值与放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
