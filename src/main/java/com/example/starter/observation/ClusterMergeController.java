package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;

/**
 * 重复观测簇字段级溯源归并 API：候选预览（只读）、归并确认、归并结果查询（主记录/成员/字段来源，只读）。
 */
@RestController
@RequestMapping("/api/observation-clusters")
@Validated
public class ClusterMergeController {

    private final ClusterMergeService clusterMergeService;

    public ClusterMergeController(ClusterMergeService clusterMergeService) {
        this.clusterMergeService = clusterMergeService;
    }

    /**
     * 候选预览：以 observedAt 为锚点返回时间窗内同 siteKey + type 的活跃未归并记录。只读、不后台聚类。
     */
    @GetMapping("/candidates")
    public ClusterPreviewResponse preview(@RequestParam @NotBlank String siteKey,
                                          @RequestParam @NotBlank String type,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant observedAt,
                                          @RequestParam(required = false) @Min(1) @Max(60) Integer windowSeconds) {
        return clusterMergeService.preview(
                new ClusterPreviewRequest(siteKey, type, observedAt, windowSeconds));
    }

    /**
     * 归并确认：提交 clusterKey、完整成员集合（记录键+generation）、新主键及每个业务字段来源。
     */
    @PostMapping("/merge")
    public ResponseEntity<ClusterMergeResponse> merge(@Valid @RequestBody ClusterMergeRequest request) {
        ClusterMergeService.ClusterOutcome outcome = clusterMergeService.merge(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按 clusterKey 查询归并结果：新主记录、成员冻结证据与字段级溯源，只读。
     */
    @GetMapping("/{clusterKey}")
    public ClusterMergeResponse getCluster(@PathVariable String clusterKey) {
        return clusterMergeService.getCluster(clusterKey);
    }
}
