package com.example.starter.maintenance;

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

import java.util.List;

/**
 * 观测站维护窗口 API：站点登记、窗口登记/更正/撤回、批量观测提交、冲突簇合并与人工裁决、
 * 明细/历史/诊断/证据查询。所有时间为 UTC，区间左闭右开；只读接口不改变状态。
 */
@RestController
@RequestMapping("/api/stations")
@Validated
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * 登记观测站（含 UTC 启用时刻）。
     */
    @PostMapping
    public ResponseEntity<StationResponse> registerStation(@Valid @RequestBody RegisterStationRequest request) {
        MaintenanceService.Outcome<StationResponse> outcome = maintenanceService.registerStation(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测站明细。
     */
    @GetMapping("/{stationId}")
    public StationResponse getStation(@PathVariable String stationId) {
        return maintenanceService.getStation(stationId);
    }

    /**
     * 登记维护窗口：UTC 左闭右开区间 + 维护版本；重叠窗口拒绝登记。
     */
    @PostMapping("/{stationId}/windows")
    public ResponseEntity<WindowResponse> registerWindow(@PathVariable String stationId,
                                                         @Valid @RequestBody RegisterWindowRequest request) {
        MaintenanceService.Outcome<WindowResponse> outcome =
                maintenanceService.registerWindow(stationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询站点全部维护窗口（历史，按开始时刻排序）。
     */
    @GetMapping("/{stationId}/windows")
    public List<WindowResponse> listWindows(@PathVariable String stationId) {
        return maintenanceService.listWindows(stationId);
    }

    /**
     * 维护窗口诊断：各状态窗口数量与封存状态。
     */
    @GetMapping("/{stationId}/windows/diagnostics")
    public WindowDiagnosticsResponse windowDiagnostics(@PathVariable String stationId) {
        return maintenanceService.windowDiagnostics(stationId);
    }

    /**
     * 查询单个维护窗口明细（含封存标记）。
     */
    @GetMapping("/windows/{windowId}")
    public WindowResponse getWindow(@PathVariable String windowId) {
        return maintenanceService.getWindow(windowId);
    }

    /**
     * 追加更正窗口：原窗口置为 SUPERSEDED；被封存快照覆盖的窗口只能更正不能修改。
     */
    @PostMapping("/windows/{windowId}/corrections")
    public ResponseEntity<WindowResponse> correctWindow(@PathVariable String windowId,
                                                        @Valid @RequestBody CorrectWindowRequest request) {
        MaintenanceService.Outcome<WindowResponse> outcome =
                maintenanceService.correctWindow(windowId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤回维护窗口：仅未被封存快照覆盖的窗口可撤回。
     */
    @PostMapping("/windows/{windowId}/withdraw")
    public ResponseEntity<WindowResponse> withdrawWindow(@PathVariable String windowId,
                                                         @Valid @RequestBody WithdrawWindowRequest request) {
        MaintenanceService.Outcome<WindowResponse> outcome =
                maintenanceService.withdrawWindow(windowId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 批量提交站点观测：逐条按提交时刻匹配维护窗口并写入维护标记；任一失败整批回滚。
     */
    @PostMapping("/observations/batch")
    public ResponseEntity<BatchSubmissionResponse> submitBatch(@Valid @RequestBody BatchSubmissionRequest request) {
        MaintenanceService.Outcome<BatchSubmissionResponse> outcome = maintenanceService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询单条站点观测原始记录（含维护标记证据）。
     */
    @GetMapping("/observations/{submissionId}")
    public StationObservationResponse getObservation(@PathVariable String submissionId) {
        return maintenanceService.getObservation(submissionId);
    }

    /**
     * 按冲突簇执行自动合并：受影响观测不可胜出；全簇受影响时结果为待人工裁决。
     */
    @PostMapping("/clusters/{clusterId}/merge")
    public ResponseEntity<MergeResultResponse> mergeCluster(@PathVariable String clusterId,
                                                            @Valid @RequestBody MergeClusterRequest request) {
        MaintenanceService.Outcome<MergeResultResponse> outcome = maintenanceService.mergeCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 人工裁决：可选择受影响观测，必须提供理由与裁决人。
     */
    @PostMapping("/clusters/{clusterId}/adjudicate")
    public ResponseEntity<AdjudicationResponse> adjudicate(@PathVariable String clusterId,
                                                           @Valid @RequestBody AdjudicateRequest request) {
        MaintenanceService.Outcome<AdjudicationResponse> outcome =
                maintenanceService.adjudicate(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询冲突簇当前状态（只读）。
     */
    @GetMapping("/clusters/{clusterId}")
    public ClusterStateResponse getCluster(@PathVariable String clusterId) {
        return maintenanceService.getCluster(clusterId);
    }

    /**
     * 查询冲突簇证据：原始观测（含维护标记）、不可变合并快照与人工裁决历史（只读）。
     */
    @GetMapping("/clusters/{clusterId}/evidence")
    public ClusterEvidenceResponse getClusterEvidence(@PathVariable String clusterId) {
        return maintenanceService.getClusterEvidence(clusterId);
    }

    /**
     * 按全局唯一裁决标识查询裁决记录。
     */
    @GetMapping("/adjudications/{adjudicationId}")
    public AdjudicationResponse getAdjudication(@PathVariable String adjudicationId) {
        return maintenanceService.getAdjudication(adjudicationId);
    }
}
