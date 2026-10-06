package com.example.starter.observation.geo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 二维坐标观测 API：创建、离线提交（整组三方合并）、删除、当前/历史版本查询，
 * 以及两条观测的一致性确认与确认记录查询。
 */
@RestController
@RequestMapping("/api/geo-observations")
@Validated
public class GeoObservationController {

    private final GeoObservationService geoObservationService;

    public GeoObservationController(GeoObservationService geoObservationService) {
        this.geoObservationService = geoObservationService;
    }

    /**
     * 创建二维坐标观测记录，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<GeoObservationResponse> create(@Valid @RequestBody CreateGeoObservationRequest request) {
        GeoObservationService.GeoWriteOutcome outcome = geoObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与坐标+矩阵完整候选值，作为一个字段组执行三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<GeoObservationResponse> merge(@PathVariable String observationId,
                                                        @Valid @RequestBody MergeGeoObservationRequest request) {
        GeoObservationService.GeoWriteOutcome outcome = geoObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除二维坐标观测记录：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<GeoObservationResponse> delete(@PathVariable String observationId,
                                                         @Valid @RequestBody DeleteGeoObservationRequest request) {
        GeoObservationService.GeoWriteOutcome outcome = geoObservationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public GeoObservationResponse getCurrent(@PathVariable String observationId) {
        return geoObservationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public GeoObservationResponse getVersion(@PathVariable String observationId,
                                             @PathVariable @Min(1) int version) {
        return geoObservationService.getVersion(observationId, version);
    }

    /**
     * 一致性确认：引用两条不同观测的当前版本，原子核对后按方向相关误差判定，
     * 成功时落库不可变确认记录（冻结两侧快照与比较值），不改动源观测。
     */
    @PostMapping("/confirmations")
    public ResponseEntity<ConsistencyConfirmation> confirm(@Valid @RequestBody ConfirmConsistencyRequest request) {
        GeoObservationService.ConfirmOutcome outcome = geoObservationService.confirm(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 confirmationId 查询不可变确认记录。
     */
    @GetMapping("/confirmations/{confirmationId}")
    public ConsistencyConfirmation getConfirmation(@PathVariable String confirmationId) {
        return geoObservationService.getConfirmation(confirmationId);
    }
}
