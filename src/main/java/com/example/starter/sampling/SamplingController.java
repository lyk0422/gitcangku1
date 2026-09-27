package com.example.starter.sampling;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测采样许可 API：许可配置/提前结束/撤销、观测提交、批量离线合并、冲突簇裁决，
 * 以及许可计数、观测许可版本与复核标记、拒绝原因查询。
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
     * 创建采样许可：同设备有效许可在网格与时间的交集不能重叠，重叠返回 409。
     */
    @PostMapping("/permits")
    public ResponseEntity<PermitResponse> createPermit(@Valid @RequestBody CreatePermitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(samplingService.createPermit(request));
    }

    /**
     * 许可提前结束：有效期终点前移，只影响之后采样，不删除已接收观测。
     */
    @PostMapping("/permits/{permitId}/end")
    public PermitResponse endPermit(@PathVariable String permitId,
                                    @Valid @RequestBody EndPermitRequest request) {
        return samplingService.endPermit(permitId, request);
    }

    /**
     * 撤销许可：未合并观测标记 PERMIT_REVIEW 并写入快照；已人工裁决簇不重算。
     */
    @PostMapping("/permits/{permitId}/revoke")
    public PermitResponse revokePermit(@PathVariable String permitId) {
        return samplingService.revokePermit(permitId);
    }

    /**
     * 查询许可配置与实时计数。
     */
    @GetMapping("/permits/{permitId}")
    public PermitResponse getPermit(@PathVariable String permitId) {
        return samplingService.getPermit(permitId);
    }

    /**
     * 查询许可下全部观测（含许可版本与复核标记）。
     */
    @GetMapping("/permits/{permitId}/observations")
    public List<SamplingObservationResponse> listPermitObservations(@PathVariable String permitId) {
        return samplingService.listPermitObservations(permitId);
    }

    /**
     * 提交观测：命中唯一有效许可并扣减其计数；失败返回 422 且不占键。
     */
    @PostMapping("/observations")
    public ResponseEntity<SubmitResponse> submitObservation(@Valid @RequestBody SubmitObservationRequest request) {
        SamplingService.SubmitOutcome outcome = samplingService.submitObservation(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按幂等键查询观测（含许可版本与复核标记）。
     */
    @GetMapping("/observations/{permitKey}")
    public SamplingObservationResponse getObservation(@PathVariable String permitKey) {
        return samplingService.getObservation(permitKey);
    }

    /**
     * 批量离线合并：预校验全部通过才写入，任一失败 422 且全部回滚。
     */
    @PostMapping("/merges")
    public ResponseEntity<BatchMergeResponse> mergeBatch(@Valid @RequestBody BatchMergeRequest request) {
        SamplingService.BatchMergeOutcome outcome = samplingService.mergeBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 冲突簇人工裁决：待裁决簇按指定内容裁决，成员观测统一置为已合并。
     */
    @PostMapping("/clusters/{clusterId}/adjudicate")
    public ClusterResponse adjudicateCluster(@PathVariable String clusterId,
                                             @Valid @RequestBody AdjudicateClusterRequest request) {
        return samplingService.adjudicateCluster(clusterId, request);
    }

    /**
     * 按标识查询冲突簇。
     */
    @GetMapping("/clusters/{clusterId}")
    public ClusterResponse getCluster(@PathVariable String clusterId) {
        return samplingService.getCluster(clusterId);
    }

    /**
     * 查询拒绝记录：设备标识与请求键均可选过滤。
     */
    @GetMapping("/rejections")
    public List<RejectionResponse> listRejections(@RequestParam(required = false) String deviceId,
                                                  @RequestParam(required = false) String requestKey) {
        return samplingService.listRejections(deviceId, requestKey);
    }
}
