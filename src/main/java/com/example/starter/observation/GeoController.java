package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测坐标基准 API：观测提交（含坐标转换与簇归属）、设备基准变更与重算、
 * 冲突簇查询与人工裁决、坐标与重算记录查询。
 */
@RestController
@RequestMapping("/api")
@Validated
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /**
     * 观测提交：携带设备、经纬度、基准版本、采集时刻与字段内容，创建观测并归簇。
     */
    @PostMapping("/observations/submit")
    public ResponseEntity<SubmissionResponse> submit(@Valid @RequestBody SubmitObservationRequest request) {
        GeoService.Outcome<SubmissionResponse> outcome = geoService.submit(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测的原始与统一坐标。
     */
    @GetMapping("/observations/{observationId}/coordinates")
    public CoordinatesResponse getCoordinates(@PathVariable String observationId) {
        return geoService.getCoordinates(observationId);
    }

    /**
     * 登记或变更设备坐标基准版本；变更时同事务重算该设备所有未人工裁决观测。
     */
    @PutMapping("/devices/{deviceId}/frame")
    public ResponseEntity<DeviceFrameResponse> updateDeviceFrame(
            @PathVariable String deviceId,
            @Valid @RequestBody UpdateDeviceFrameRequest request) {
        GeoService.Outcome<DeviceFrameResponse> outcome = geoService.updateDeviceFrame(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按设备查询基准重算记录列表。
     */
    @GetMapping("/devices/{deviceId}/frame-recalcs")
    public List<FrameRecalcResponse> listRecalcs(@PathVariable String deviceId) {
        return geoService.listRecalcs(deviceId);
    }

    /**
     * 按标识查询基准重算记录。
     */
    @GetMapping("/frame-recalcs/{recalcId}")
    public FrameRecalcResponse getRecalc(@PathVariable String recalcId) {
        return geoService.getRecalc(recalcId);
    }

    /**
     * 查询冲突簇：成员、当前胜出记录与人工裁决状态。
     */
    @GetMapping("/clusters/{clusterId}")
    public ClusterResponse getCluster(@PathVariable String clusterId) {
        return geoService.getCluster(clusterId);
    }

    /**
     * 冲突簇人工裁决：选定簇内一条观测记录作为胜出记录，结论不被自动覆盖。
     */
    @PostMapping("/clusters/{clusterId}/resolve")
    public ResponseEntity<ClusterResponse> resolveCluster(@PathVariable String clusterId,
                                                          @Valid @RequestBody ResolveClusterRequest request) {
        GeoService.Outcome<ClusterResponse> outcome = geoService.resolveCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
