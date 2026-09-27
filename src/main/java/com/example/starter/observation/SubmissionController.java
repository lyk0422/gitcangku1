package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 离线观测提交 API：批量提交（围栏匹配 + 自动合并）、提交详情/站点历史查询、人工裁决。
 */
@RestController
@Validated
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * 批量提交离线观测：任一格式错误整批回滚；围栏内自动合并，围栏外保存 GEOFENCE_REJECTED 证据。
     */
    @PostMapping("/api/observation-submissions/batch")
    public ResponseEntity<BatchSubmissionResponse> submitBatch(
            @Valid @RequestBody BatchSubmissionRequest request) {
        SubmissionService.BatchOutcome outcome = submissionService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询提交详情（含原始坐标、匹配结果与合并版本）。
     */
    @GetMapping("/api/observation-submissions/{submissionId}")
    public SubmissionResponse getSubmission(@PathVariable String submissionId) {
        return submissionService.getSubmission(submissionId);
    }

    /**
     * 按站点查询提交历史，按提交时刻与批内顺序稳定排序。
     */
    @GetMapping("/api/sites/{siteId}/observation-submissions")
    public List<SubmissionResponse> listBySite(@PathVariable String siteId) {
        return submissionService.listBySite(siteId);
    }

    /**
     * 人工裁决：接受被围栏拒绝的提交（必须填写理由），原子完成合并与不可变裁决记录。
     */
    @PostMapping("/api/observation-submissions/{submissionId}/adjudications")
    public ResponseEntity<AdjudicationResponse> adjudicate(
            @PathVariable String submissionId,
            @Valid @RequestBody AdjudicateSubmissionRequest request) {
        SubmissionService.AdjudicationOutcome outcome = submissionService.adjudicate(submissionId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询提交的裁决历史，按裁决时刻稳定排序。
     */
    @GetMapping("/api/observation-submissions/{submissionId}/adjudications")
    public List<AdjudicationResponse> listAdjudications(@PathVariable String submissionId) {
        return submissionService.listAdjudications(submissionId);
    }
}
