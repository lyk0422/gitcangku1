package com.example.starter.observation.geofence;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测地理围栏 API：围栏配置与版本历史、离线观测批量提交、人工裁决、证据与裁决查询。
 * 所有查询接口只读，不修改状态。
 */
@RestController
@RequestMapping("/api/geofence")
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    /**
     * 创建围栏，初始版本为 1。
     */
    @PostMapping("/fences")
    public ResponseEntity<FenceResponse> createFence(@Valid @RequestBody CreateFenceRequest request) {
        GeofenceService.GeofenceOutcome<FenceResponse> outcome = geofenceService.createFence(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 变更围栏：追加一个不可变新版本。
     */
    @PostMapping("/fences/{fenceId}/updates")
    public ResponseEntity<FenceResponse> updateFence(@PathVariable String fenceId,
                                                     @Valid @RequestBody UpdateFenceRequest request) {
        GeofenceService.GeofenceOutcome<FenceResponse> outcome = geofenceService.updateFence(fenceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询围栏最新版本。
     */
    @GetMapping("/fences/{fenceId}")
    public FenceResponse getFence(@PathVariable String fenceId) {
        return geofenceService.getFence(fenceId);
    }

    /**
     * 查询围栏全部版本，按版本号升序。
     */
    @GetMapping("/fences/{fenceId}/versions")
    public List<FenceResponse> listFenceVersions(@PathVariable String fenceId) {
        return geofenceService.listFenceVersions(fenceId);
    }

    /**
     * 查询站点全部围栏版本，按生效起始时刻、围栏标识、版本号升序。
     */
    @GetMapping("/sites/{siteId}/fences")
    public List<FenceResponse> listSiteFences(@PathVariable String siteId) {
        return geofenceService.listSiteFences(siteId);
    }

    /**
     * 批量提交离线观测：任一格式错误整批回滚。
     */
    @PostMapping("/observations/batch")
    public ResponseEntity<BatchObservationResponse> submitBatch(
            @Valid @RequestBody BatchObservationRequest request) {
        GeofenceService.GeofenceOutcome<BatchObservationResponse> outcome = geofenceService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测证据明细。
     */
    @GetMapping("/observations/{observationId}")
    public ObservationEvidenceResponse getObservation(@PathVariable String observationId) {
        return geofenceService.getObservation(observationId);
    }

    /**
     * 按站点查询观测证据，可按状态过滤；按提交时刻、观测标识升序。
     */
    @GetMapping("/sites/{siteId}/observations")
    public List<ObservationEvidenceResponse> listSiteObservations(
            @PathVariable String siteId,
            @RequestParam(required = false) String status) {
        return geofenceService.listSiteObservations(siteId, status);
    }

    /**
     * 人工接受一条被围栏拒绝的观测，必须填写理由。
     */
    @PostMapping("/observations/{observationId}/adjudicate")
    public ResponseEntity<AdjudicationResponse> adjudicate(@PathVariable String observationId,
                                                           @Valid @RequestBody AdjudicateRequest request) {
        GeofenceService.GeofenceOutcome<AdjudicationResponse> outcome =
                geofenceService.adjudicate(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 adjudicationId 查询裁决记录。
     */
    @GetMapping("/adjudications/{adjudicationId}")
    public AdjudicationResponse getAdjudication(@PathVariable String adjudicationId) {
        return geofenceService.getAdjudication(adjudicationId);
    }

    /**
     * 按观测标识查询裁决历史，按裁决时刻升序。
     */
    @GetMapping("/observations/{observationId}/adjudications")
    public List<AdjudicationResponse> listAdjudications(@PathVariable String observationId) {
        return geofenceService.listAdjudications(observationId);
    }
}
