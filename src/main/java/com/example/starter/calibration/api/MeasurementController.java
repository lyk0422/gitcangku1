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

import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.RevisionChainItem;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;
import com.example.starter.calibration.service.RevisionService;

/**
 * 测量接口：提交、批量放行、历史明细、当前可用结果查询、后继修订与修订链查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final RevisionService revisions;

    public MeasurementController(MeasurementService measurements, ReleaseService releases,
                                 RevisionService revisions) {
        this.measurements = measurements;
        this.releases = releases;
        this.revisions = revisions;
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
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始测量、计算值、显示值与放行历史；不存在 404。
     * version 缺省时返回该测量键的最新版本。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key,
                                      @RequestParam(required = false) Integer version) {
        return measurements.detail(key, version);
    }

    /**
     * 为被驳回测量创建后继修订：201；参数非法 400；前驱不存在 404；
     * 前驱未驳回/非原提交人/已存在后继 409。仅允许修改测量值及说明。
     */
    @PostMapping("/{key}/revisions")
    public ResponseEntity<MeasurementResponse> createRevision(@PathVariable String key,
                                                              @RequestBody CreateRevisionRequest request,
                                                              @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(revisions.create(key, request, actor));
    }

    /**
     * 修订链只读查询：按版本升序返回该测量键的全部版本；键不存在 404。
     */
    @GetMapping("/{key}/revisions")
    public List<RevisionChainItem> revisionChain(@PathVariable String key) {
        return revisions.chain(key);
    }
}
