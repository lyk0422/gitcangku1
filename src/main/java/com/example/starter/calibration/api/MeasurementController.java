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
import com.example.starter.calibration.api.dto.VersionedReleaseResponse;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、修订、版本化放行、旧放行入口、版本历史、历史明细、当前可用结果查询。
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
     * 修订测量（新版本）：201。仅原提交人（X-Actor-Id）可发起，携带 expectedRevision、requestId 与非空原因。
     * 非法参数 400；不存在 404；权限/版本冲突/幂等改参 409；重匹配无有效证书 422。
     */
    @PostMapping("/{key}/revisions")
    public ResponseEntity<MeasurementResponse> revise(@PathVariable String key,
                                                      @RequestBody ReviseMeasurementRequest request,
                                                      @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.revise(key, request, actor));
    }

    /**
     * 版本历史：按版本号升序返回全部不可变版本；不存在 404。
     */
    @GetMapping("/{key}/revisions")
    public MeasurementHistoryResponse history(@PathVariable String key) {
        return measurements.history(key);
    }

    /**
     * 版本化批量放行（1～50 个不同测量键及其 revision，原子）：200；批次非法 400；
     * 任一项不是最新 PENDING 合格版本、证书已撤销或放行人冲突时整批拒绝 409 并返回逐项原因。
     */
    @PostMapping("/release/versions")
    public VersionedReleaseResponse releaseVersions(@RequestBody VersionedReleaseRequest request,
                                                    @RequestHeader("X-Actor-Id") String actor) {
        return releases.releaseVersions(request.items(), actor);
    }

    /**
     * 旧批量放行入口（1～50 条，原子）：从未修订的测量保持原语义；
     * 已有修订的键对该项返回 409 REVISION_REQUIRED，要求使用版本化放行入口。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 当前可用结果：每键至多一条最新版本，已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：默认返回最新版本并带 revision；可通过 revision 查询旧版本。不存在 404。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key,
                                      @RequestParam(required = false) Integer revision) {
        return measurements.detail(key, revision);
    }
}
