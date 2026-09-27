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
 * 观测采样许可 API：许可创建/提前结束/撤销/查询、采样提交、批量离线合并、冲突簇查询与人工裁决。
 * 既有观测创建/三方合并/删除/冲突解决接口由 {@link ObservationController} 提供，二者共用同一套表。
 */
@RestController
@RequestMapping("/api/sampling")
@Validated
public class SamplingController {

    private final SamplingService samplingService;

    public SamplingController(SamplingService samplingService) {
        this.samplingService = samplingService;
    }

    /**
     * 创建采样许可；同设备有效许可在网格与时间交集重叠返回 409（reason=PERMIT_OVERLAP）。
     */
    @PostMapping("/permits")
    public ResponseEntity<PermitResponse> createPermit(@Valid @RequestBody CreatePermitRequest request) {
        SamplingService.PermitOutcome outcome = samplingService.createPermit(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询许可计数与授权范围。
     */
    @GetMapping("/permits/{permitId}")
    public PermitResponse getPermit(@PathVariable String permitId) {
        return samplingService.getPermit(permitId);
    }

    /**
     * 提前结束许可：只影响结束时刻（不含）之后的采样。
     */
    @PostMapping("/permits/{permitId}/terminate")
    public ResponseEntity<PermitResponse> terminatePermit(@PathVariable String permitId,
                                                          @Valid @RequestBody TerminatePermitRequest request) {
        SamplingService.PermitOutcome outcome = samplingService.terminatePermit(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销许可：未合并观测进入 PERMIT_REVIEW 并写复核快照，已人工裁决簇不重算。
     */
    @PostMapping("/permits/{permitId}/revoke")
    public ResponseEntity<SamplingService.RevokeResult> revokePermit(
            @PathVariable String permitId,
            @Valid @RequestBody RevokePermitRequest request) {
        SamplingService.RevokeOutcome outcome = samplingService.revokePermit(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 采样观测提交：必须唯一命中有效许可并在容量上限内，返回 permitKey 与许可剩余计数。
     */
    @PostMapping("/observations")
    public ResponseEntity<SamplingService.SampleResult> submitSample(
            @Valid @RequestBody SampleSubmitRequest request) {
        SamplingService.SampleOutcome outcome = samplingService.submitSample(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测的许可版本、业务状态、复核标记与拒绝/复核原因所需信息。
     */
    @GetMapping("/observations/{observationId}")
    public ObservationResponse getObservation(@PathVariable String observationId) {
        return samplingService.getObservation(observationId);
    }

    /**
     * 批量离线合并：整体预校验后原子应用；无许可/命中不唯一/超限返回 422 并整批回滚。
     */
    @PostMapping("/batch-merges")
    public ResponseEntity<BatchMergeResponse> batchMerge(@Valid @RequestBody BatchMergeRequest request) {
        SamplingService.BatchOutcome outcome = samplingService.batchMerge(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按批量请求标识查询本批冲突簇。
     */
    @GetMapping("/batch-merges/{batchRequestId}/clusters")
    public List<ClusterResponse> listClusters(@PathVariable String batchRequestId) {
        return samplingService.listClustersByBatch(batchRequestId);
    }

    /**
     * 按簇标识查询冲突簇。
     */
    @GetMapping("/clusters/{clusterId}")
    public ClusterResponse getCluster(@PathVariable String clusterId) {
        return samplingService.getCluster(clusterId);
    }

    /**
     * 冲突簇人工裁决：原子写入观测新版本（或无变化）、不可变解决记录并将簇置 RESOLVED。
     */
    @PostMapping("/clusters/{clusterId}/resolve")
    public ResponseEntity<SamplingService.ResolveClusterResult> resolveCluster(
            @PathVariable String clusterId,
            @Valid @RequestBody ResolveConflictRequest request) {
        SamplingService.ResolveClusterOutcome outcome = samplingService.resolveCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
