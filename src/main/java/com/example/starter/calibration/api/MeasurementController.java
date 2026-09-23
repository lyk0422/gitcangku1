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

import com.example.starter.calibration.api.dto.MeasurementHistoryResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.api.dto.VersionedReleaseRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、修订、批量放行（旧入口与版本化入口）、版本历史、当前可用结果查询。
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
     * 提交测量（第 1 版）：201；参数非法 400；测量键重复 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 修订测量（新版本）：201；参数非法 400；不存在 404；
     * 非原提交人/期望版本过期/幂等改参 409；按原测量时刻无未撤销证书 422。
     * 原提交人通过 X-Actor-Id 携带 expectedRevision、requestId 及非空原因。
     */
    @PostMapping("/revisions")
    public ResponseEntity<MeasurementResponse> revise(@RequestBody ReviseMeasurementRequest request,
                                                      @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.revise(request, actor));
    }

    /**
     * 旧批量放行入口（按键）：对从未修订的测量可用；已有修订的键整批 409 要求显式版本。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 版本化批量放行：每项指定 measurementKey 与 revision，整批原子生效；
     * 任一项不是最新 PENDING 版本、不合格、证书已撤销或放行人冲突均整批 409 并返回逐项原因。
     */
    @PostMapping("/release/versions")
    public ReleaseResponse releaseVersioned(@RequestBody VersionedReleaseRequest request,
                                            @RequestHeader("X-Actor-Id") String actor) {
        return releases.releaseVersioned(request.items(), actor);
    }

    /**
     * 当前可用结果：每键至多一行最新版本（已放行且证书未撤销）；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 版本历史：返回全部修订版本明细（默认最新排最后）；不存在 404。
     */
    @GetMapping("/{key}/revisions")
    public MeasurementHistoryResponse history(@PathVariable String key) {
        return measurements.history(key);
    }

    /**
     * 指定版本明细；键或版本不存在 404。
     */
    @GetMapping("/{key}/revisions/{revision}")
    public MeasurementResponse detailRevision(@PathVariable String key, @PathVariable int revision) {
        return measurements.detailRevision(key, revision);
    }

    /**
     * 历史明细（默认最新版本）：原始测量、计算值、显示值与该版本放行历史；不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
