package com.example.starter.permit;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观测采样许可 API：许可管理、许可观测提交/批量离线合并、冲突簇裁决与查询。
 */
@RestController
@RequestMapping("/api/sampling")
@Validated
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    @PostMapping("/permits")
    public ResponseEntity<PermitResponse> createPermit(@Valid @RequestBody CreatePermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.createPermit(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/permits/{permitId}/end")
    public ResponseEntity<PermitResponse> endPermit(@PathVariable String permitId,
                                                    @Valid @RequestBody EndPermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.endPermit(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/permits/{permitId}/revoke")
    public ResponseEntity<PermitResponse> revokePermit(@PathVariable String permitId,
                                                       @Valid @RequestBody RevokePermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.revokePermit(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/permits/{permitId}")
    public PermitResponse getPermit(@PathVariable String permitId) {
        return permitService.getPermit(permitId);
    }

    @PostMapping("/observations")
    public ResponseEntity<PermitObservationResponse> submit(@Valid @RequestBody SubmitObservationRequest request) {
        PermitService.SubmitOutcome outcome = permitService.submit(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/observations/batch-merge")
    public ResponseEntity<BatchMergeResponse> batchMerge(@Valid @RequestBody BatchMergeRequest request) {
        PermitService.BatchOutcome outcome = permitService.batchMerge(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/observations/{observationKey}")
    public PermitObservationResponse getObservation(@PathVariable String observationKey) {
        return permitService.getObservation(observationKey);
    }

    /**
     * 查询某观测坐标的拒绝原因历史（按拒绝时刻倒序）；观测从未接收成功时仍可查询。
     */
    @GetMapping("/observations/{observationKey}/rejections")
    public java.util.List<RejectionEntry> listRejections(@PathVariable String observationKey) {
        return permitService.listRejections(observationKey);
    }

    @PostMapping("/clusters/{clusterId}/resolve")
    public ResponseEntity<ClusterResolutionResponse> resolveCluster(@PathVariable String clusterId,
                                                                    @Valid @RequestBody ResolveClusterRequest request) {
        PermitService.ClusterOutcome outcome = permitService.resolveCluster(clusterId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/clusters/{clusterId}")
    public ClusterResolutionResponse getClusterResolution(@PathVariable String clusterId) {
        return permitService.getClusterResolution(clusterId);
    }
}
