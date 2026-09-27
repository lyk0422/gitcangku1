package com.example.starter.maintenance;

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
 * 观测站维护窗口 API：站点与窗口登记/更正/删除、观测批量提交、冲突簇合并与人工裁决，以及明细/历史/诊断只读查询。
 * 统一挂在 /api/maintenance 前缀下，与既有现场观测 API（/api/observations）隔离。
 */
@RestController
@RequestMapping("/api/maintenance")
@Validated
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 登记观测站及 UTC 启用时刻。
     */
    @PostMapping("/stations")
    public ResponseEntity<StationResponse> registerStation(@Valid @RequestBody RegisterStationRequest request) {
        MaintenanceService.WriteOutcome<StationResponse> outcome = maintenanceService.registerStation(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测站。
     */
    @GetMapping("/stations/{stationId}")
    public StationResponse getStation(@PathVariable String stationId) {
        return maintenanceService.getStation(stationId);
    }

    /**
     * 查询站点生效窗口明细（只读诊断）。
     */
    @GetMapping("/stations/{stationId}/windows")
    public List<MaintenanceWindowResponse> listActiveWindows(@PathVariable String stationId) {
        return maintenanceService.listActiveWindows(stationId);
    }

    /**
     * 为站点登记 UTC 左闭右开维护窗口。
     */
    @PostMapping("/stations/{stationId}/windows")
    public ResponseEntity<MaintenanceWindowResponse> registerWindow(@PathVariable String stationId,
                                                                    @Valid @RequestBody RegisterWindowRequest request) {
        MaintenanceService.WriteOutcome<MaintenanceWindowResponse> outcome =
                maintenanceService.registerWindow(stationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 对已封存历史窗口追加更正窗口。
     */
    @PostMapping("/windows/corrections")
    public ResponseEntity<MaintenanceWindowResponse> correctWindow(@Valid @RequestBody CorrectWindowRequest request) {
        MaintenanceService.WriteOutcome<MaintenanceWindowResponse> outcome =
                maintenanceService.correctWindow(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除未封存的生效窗口。
     */
    @PostMapping("/windows/{windowId}/delete")
    public ResponseEntity<MaintenanceWindowResponse> deleteWindow(@PathVariable String windowId,
                                                                  @Valid @RequestBody DeleteWindowRequest request) {
        MaintenanceService.WriteOutcome<MaintenanceWindowResponse> outcome =
                maintenanceService.deleteWindow(windowId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询单个维护窗口。
     */
    @GetMapping("/windows/{windowId}")
    public MaintenanceWindowResponse getWindow(@PathVariable String windowId) {
        return maintenanceService.getWindow(windowId);
    }

    /**
     * 批量提交观测：逐条按提交时刻匹配维护窗口，任一失败整批回滚。
     */
    @PostMapping("/observations/batch")
    public ResponseEntity<SubmitObservationsResponse> submitObservations(
            @Valid @RequestBody SubmitObservationsRequest request) {
        MaintenanceService.WriteOutcome<SubmitObservationsResponse> outcome =
                maintenanceService.submitObservations(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询单条原始观测及固化维护标记。
     */
    @GetMapping("/observations/{observationId}")
    public ObservationResultResponse getObservation(@PathVariable String observationId) {
        return maintenanceService.getObservation(observationId);
    }

    /**
     * 查询冲突簇当前观测明细（只读诊断）。
     */
    @GetMapping("/clusters/{clusterId}/observations")
    public List<ObservationResultResponse> listClusterObservations(@PathVariable String clusterId) {
        return maintenanceService.listClusterObservations(clusterId);
    }

    /**
     * 合并冲突簇：全簇受维护影响时结果为待人工裁决。
     */
    @PostMapping("/clusters/{clusterId}/merge")
    public ResponseEntity<SnapshotResponse> mergeCluster(@PathVariable String clusterId,
                                                         @Valid @RequestBody MergeClusterRequest request) {
        MaintenanceService.WriteOutcome<SnapshotResponse> outcome =
                maintenanceService.mergeCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询冲突簇全部合并快照历史。
     */
    @GetMapping("/clusters/{clusterId}/snapshots")
    public List<SnapshotResponse> listSnapshots(@PathVariable String clusterId) {
        return maintenanceService.listSnapshots(clusterId);
    }

    /**
     * 按主键查询合并快照（含固化证据维护标记与窗口版本）。
     */
    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotResponse getSnapshot(@PathVariable long snapshotId) {
        return maintenanceService.getSnapshot(snapshotId);
    }

    /**
     * 人工裁决待处理冲突簇：允许选择受影响观测，理由与裁决人必填。
     */
    @PostMapping("/clusters/{clusterId}/adjudications")
    public ResponseEntity<AdjudicationResponse> adjudicateCluster(@PathVariable String clusterId,
                                                                  @Valid @RequestBody AdjudicateClusterRequest request) {
        MaintenanceService.WriteOutcome<AdjudicationResponse> outcome =
                maintenanceService.adjudicateCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一裁决标识查询裁决记录。
     */
    @GetMapping("/adjudications/{adjudicationId}")
    public AdjudicationResponse getAdjudication(@PathVariable String adjudicationId) {
        return maintenanceService.getAdjudication(adjudicationId);
    }

    /**
     * 按冲突簇查询裁决历史。
     */
    @GetMapping("/clusters/{clusterId}/adjudications")
    public List<AdjudicationResponse> listAdjudications(@PathVariable String clusterId) {
        return maintenanceService.listAdjudications(clusterId);
    }
}
