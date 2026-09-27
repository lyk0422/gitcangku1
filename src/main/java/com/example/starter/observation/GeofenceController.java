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
 * 观测地理围栏 API：站点矩形围栏配置、离线观测批量提交（按采集时刻匹配围栏）、
 * 被拒提交人工接受，以及明细/历史/诊断查询。
 */
@RestController
@RequestMapping("/api")
@Validated
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    /**
     * 创建站点矩形围栏：422 表示经纬度/边界/生效区间非法，409 表示同站点生效围栏重叠或标识重复。
     */
    @PostMapping("/geofences")
    public ResponseEntity<GeofenceResponse> createGeofence(
            @Valid @RequestBody CreateGeofenceRequest request) {
        GeofenceService.Outcome<GeofenceResponse> outcome = geofenceService.createGeofence(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询围栏明细。
     */
    @GetMapping("/geofences/{fenceId}")
    public GeofenceResponse getGeofence(@PathVariable String fenceId) {
        return geofenceService.getGeofence(fenceId);
    }

    /**
     * 按站点查询围栏历史，按生效起点与围栏标识稳定排序。
     */
    @GetMapping("/sites/{siteId}/geofences")
    public List<GeofenceResponse> listGeofences(@PathVariable String siteId) {
        return geofenceService.listGeofences(siteId);
    }

    /**
     * 批量离线观测提交：可混合站点，按提交顺序裁决；任一明细非法整批回滚。
     */
    @PostMapping("/geofence-submissions")
    public ResponseEntity<BatchGeofenceSubmissionResponse> submitBatch(
            @Valid @RequestBody BatchGeofenceSubmissionRequest request) {
        GeofenceService.Outcome<BatchGeofenceSubmissionResponse> outcome =
                geofenceService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询提交明细（含不可变原始证据）。
     */
    @GetMapping("/geofence-submissions/{submissionId}")
    public GeofenceSubmissionResponse getSubmission(@PathVariable String submissionId) {
        return geofenceService.getSubmission(submissionId);
    }

    /**
     * 人工接受被拒提交：必须填写理由，接受后按提交保存的候选值执行三方合并。
     */
    @PostMapping("/geofence-submissions/{submissionId}/accept")
    public ResponseEntity<GeofenceSubmissionResponse> accept(
            @PathVariable String submissionId,
            @Valid @RequestBody AcceptGeofenceSubmissionRequest request) {
        GeofenceService.Outcome<GeofenceSubmissionResponse> outcome =
                geofenceService.accept(submissionId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按站点查询提交历史，按受理时刻与提交标识稳定排序。
     */
    @GetMapping("/sites/{siteId}/geofence-submissions")
    public List<GeofenceSubmissionResponse> listSubmissions(@PathVariable String siteId) {
        return geofenceService.listSubmissions(siteId);
    }

    /**
     * 站点提交诊断：各状态数量均为实际值。
     */
    @GetMapping("/sites/{siteId}/geofence-diagnostics")
    public GeofenceDiagnosticsResponse diagnostics(@PathVariable String siteId) {
        return geofenceService.diagnostics(siteId);
    }
}
