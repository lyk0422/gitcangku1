package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测坐标基准 API：基准登记、设备基准修改（同事务重算）、带坐标观测提交、
 * 冲突簇人工裁决，以及坐标/簇/重算记录查询。
 */
@RestController
@RequestMapping("/api/geo")
@Validated
public class GeoObservationController {

    private final GeoObservationService geoObservationService;

    public GeoObservationController(GeoObservationService geoObservationService) {
        this.geoObservationService = geoObservationService;
    }

    /**
     * 登记坐标基准版本（公开固定偏移参数）。
     */
    @PostMapping("/frames")
    public ResponseEntity<FrameResponse> registerFrame(@Valid @RequestBody RegisterFrameRequest request) {
        GeoObservationService.TypedOutcome<FrameResponse> outcome = geoObservationService.registerFrame(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 修改设备坐标基准版本：同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属。
     */
    @PostMapping("/devices/{deviceId}/frame")
    public ResponseEntity<DeviceFrameResponse> updateDeviceFrame(
            @PathVariable String deviceId,
            @Valid @RequestBody UpdateDeviceFrameRequest request) {
        GeoObservationService.TypedOutcome<DeviceFrameResponse> outcome =
                geoObservationService.updateDeviceFrame(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 提交带坐标观测：含经纬度、基准版本、采集时刻与字段内容。
     */
    @PostMapping("/devices/{deviceId}/observations")
    public ResponseEntity<GeoObservationResponse> submit(
            @PathVariable String deviceId,
            @Valid @RequestBody SubmitGeoObservationRequest request) {
        GeoObservationService.TypedOutcome<GeoObservationResponse> outcome =
                geoObservationService.submit(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 冲突簇人工裁决：指定胜出观测；已裁决簇的结论不被覆盖。
     */
    @PostMapping("/clusters/{clusterId}/resolve")
    public ResponseEntity<ClusterResponse> resolveCluster(
            @PathVariable String clusterId,
            @Valid @RequestBody ResolveClusterRequest request) {
        GeoObservationService.TypedOutcome<ClusterResponse> outcome =
                geoObservationService.resolveCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询坐标观测：原始坐标（含原基准版本）与统一基准坐标。
     */
    @GetMapping("/observations/{observationId}")
    public GeoObservationResponse getObservation(@PathVariable String observationId) {
        return geoObservationService.getObservation(observationId);
    }

    /**
     * 查询单个冲突簇：成员、当前胜出观测与人工裁决状态。
     */
    @GetMapping("/clusters/{clusterId}")
    public ClusterResponse getCluster(@PathVariable String clusterId) {
        return geoObservationService.getCluster(clusterId);
    }

    /**
     * 查询全部冲突簇。
     */
    @GetMapping("/clusters")
    public List<ClusterResponse> listClusters() {
        return geoObservationService.listClusters();
    }

    /**
     * 按标识查询基准重算记录。
     */
    @GetMapping("/recalcs/{recalcId}")
    public RecalcRecordResponse getRecalc(@PathVariable String recalcId) {
        return geoObservationService.getRecalc(recalcId);
    }

    /**
     * 按设备查询基准重算历史。
     */
    @GetMapping("/devices/{deviceId}/recalcs")
    public List<RecalcRecordResponse> listRecalcs(@PathVariable String deviceId) {
        return geoObservationService.listRecalcs(deviceId);
    }
}
