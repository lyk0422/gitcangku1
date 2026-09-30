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

import com.example.starter.calibration.api.dto.EnvironmentReleaseRequest;
import com.example.starter.calibration.api.dto.EnvironmentReleaseResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.EnvironmentReleaseService;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 测量接口：提交、批量放行、环境补偿放行、历史明细、当前可用结果查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final EnvironmentReleaseService environmentReleases;

    public MeasurementController(MeasurementService measurements,
                                 ReleaseService releases,
                                 EnvironmentReleaseService environmentReleases) {
        this.measurements = measurements;
        this.releases = releases;
        this.environmentReleases = environmentReleases;
    }

    /**
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；任一项不满足条件整批拒绝 409 并返回各项原因。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(request.keys(), actor);
    }

    /**
     * 环境补偿放行（1～50 条，原子）：在证书温湿度范围内且当前唯一生效系数版本存在时计算并固化；
     * 越界/无生效版本/多版本命中返回 422（携带实际读数与原因），不生成放行记录；
     * 同 requestId 重放同快照返回首次结果，改读数/放行人 409；其他状态类冲突 409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/environment-release")
    public EnvironmentReleaseResponse environmentRelease(@RequestBody EnvironmentReleaseRequest request,
                                                         @RequestHeader("X-Actor-Id") String actor) {
        return environmentReleases.release(request, actor);
    }

    /**
     * 当前可用结果：已放行（含环境补偿放行）且证书未撤销；可按仪器过滤。
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
