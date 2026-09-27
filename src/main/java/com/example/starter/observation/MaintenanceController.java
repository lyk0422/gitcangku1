package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 观测站维护窗口 API：站点登记、维护窗口登记/追加更正、观测批量提交、冲突簇合并/人工裁决，
 * 以及窗口历史/有效集诊断、提交证据、批次明细与合并历史等只读查询。
 */
@RestController
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 登记观测站及其 UTC 启用时刻。
     */
    @PostMapping("/api/stations")
    public ResponseEntity<StationResponse> registerStation(
            @Valid @RequestBody RegisterStationRequest request) {
        MaintenanceService.MaintenanceOutcome<StationResponse> outcome =
                maintenanceService.registerStation(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询站点（只读）。
     */
    @GetMapping("/api/stations/{stationId}")
    public StationResponse getStation(@PathVariable String stationId) {
        return maintenanceService.getStation(stationId);
    }

    /**
     * 登记一条 UTC 左闭右开维护窗口。
     */
    @PostMapping("/api/stations/{stationId}/maintenance-windows")
    public ResponseEntity<MaintenanceWindowResponse> registerWindow(
            @PathVariable String stationId,
            @Valid @RequestBody RegisterMaintenanceWindowRequest request) {
        if (!stationId.equals(request.stationId())) {
            throw ApiException.badRequest("stationId in path and body must be identical");
        }
        MaintenanceService.MaintenanceOutcome<MaintenanceWindowResponse> outcome =
                maintenanceService.registerWindow(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 追加更正窗口（替换时间区间或撤回）；历史窗口不修改，只追加新版本行。
     */
    @PostMapping("/api/maintenance-windows/corrections")
    public ResponseEntity<MaintenanceWindowResponse> correctWindow(
            @Valid @RequestBody CorrectMaintenanceWindowRequest request) {
        MaintenanceService.MaintenanceOutcome<MaintenanceWindowResponse> outcome =
                maintenanceService.correctWindow(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询站点窗口变更历史：登记窗口与每条更正/撤回行，按维护版本升序（只读）。
     */
    @GetMapping("/api/stations/{stationId}/maintenance-windows")
    public List<MaintenanceWindowResponse> listWindows(@PathVariable String stationId) {
        return maintenanceService.listWindows(stationId);
    }

    /**
     * 诊断查询：截至指定 UTC 时刻按已提交窗口版本折叠的有效窗口集合及命中窗口（只读）。
     */
    @GetMapping("/api/stations/{stationId}/maintenance-windows/effective")
    public MaintenanceEffectiveAtResponse effectiveWindowsAt(
            @PathVariable String stationId,
            @RequestParam("atUtc") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant atUtc) {
        return maintenanceService.effectiveWindowsAt(stationId, atUtc);
    }

    /**
     * 观测批量提交：逐条匹配维护窗口并固化标记；任一明细失败整批回滚。
     */
    @PostMapping("/api/observation-submissions/batch")
    public ResponseEntity<BatchSubmitResponse> submitBatch(
            @Valid @RequestBody SubmitObservationBatchRequest request) {
        MaintenanceService.MaintenanceOutcome<BatchSubmitResponse> outcome =
                maintenanceService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 证据查询：按提交标识查看原始观测与维护标记（只读）。
     */
    @GetMapping("/api/observation-submissions/{submissionId}")
    public SubmissionResponse getSubmission(@PathVariable String submissionId) {
        return maintenanceService.getSubmission(submissionId);
    }

    /**
     * 批次明细查询：按批量提交 requestId 查看逐条结果（只读）。
     */
    @GetMapping("/api/observation-submissions/batches/{requestId}")
    public BatchSubmitResponse getBatch(@PathVariable String requestId) {
        return maintenanceService.getBatch(requestId);
    }

    /**
     * 冲突簇合并：受影响观测不可自动胜出，全簇受影响则待人工裁决。
     */
    @PostMapping("/api/observations/{observationId}/cluster-merge")
    public ResponseEntity<ClusterMergeResponse> mergeCluster(
            @PathVariable String observationId,
            @Valid @RequestBody MergeClusterRequest request) {
        if (!observationId.equals(request.observationId())) {
            throw ApiException.badRequest("observationId in path and body must be identical");
        }
        MaintenanceService.MaintenanceOutcome<ClusterMergeResponse> outcome =
                maintenanceService.mergeCluster(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 人工裁决：可选择受影响观测，但必须写入裁决人和理由。
     */
    @PostMapping("/api/cluster-merges/{mergeId}/decision")
    public ResponseEntity<ClusterMergeResponse> decide(
            @PathVariable String mergeId,
            @Valid @RequestBody ManualDecisionRequest request) {
        if (!mergeId.equals(request.mergeId())) {
            throw ApiException.badRequest("mergeId in path and body must be identical");
        }
        MaintenanceService.MaintenanceOutcome<ClusterMergeResponse> outcome =
                maintenanceService.decide(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询单次簇合并/裁决记录（只读）。
     */
    @GetMapping("/api/cluster-merges/{mergeId}")
    public ClusterMergeResponse getClusterMerge(@PathVariable String mergeId) {
        return maintenanceService.getClusterMerge(mergeId);
    }

    /**
     * 查询观测记录的冲突簇合并历史（只读）。
     */
    @GetMapping("/api/observations/{observationId}/cluster-merges")
    public List<ClusterMergeResponse> listClusterMerges(@PathVariable String observationId) {
        return maintenanceService.listClusterMerges(observationId);
    }
}
