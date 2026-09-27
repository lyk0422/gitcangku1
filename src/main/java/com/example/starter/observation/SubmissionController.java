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
 * 离线观测提交 API：批量提交（围栏匹配 + 自动合并）、人工裁决、明细/历史/诊断查询。
 */
@RestController
@Validated
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * 批量离线提交：可混合站点；任一项格式/语义错误整批回滚。
     */
    @PostMapping("/api/submissions/batch")
    public ResponseEntity<BatchSubmissionResponse> submitBatch(
            @Valid @RequestBody BatchSubmitObservationsRequest request) {
        SubmissionService.BatchOutcome outcome = submissionService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 人工裁决：接受围栏拒绝的提交，须写理由。
     */
    @PostMapping("/api/submissions/{submissionId}/adjudicate")
    public ResponseEntity<AdjudicationResponse> adjudicate(
            @PathVariable String submissionId,
            @Valid @RequestBody AdjudicateSubmissionRequest request) {
        SubmissionService.AdjudicationOutcome outcome = submissionService.adjudicate(submissionId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询提交明细（含不可变原始坐标、采集/提交时刻与匹配结果）。
     */
    @GetMapping("/api/submissions/{submissionId}")
    public SubmissionResponse getSubmission(@PathVariable String submissionId) {
        return submissionService.getSubmission(submissionId);
    }

    /**
     * 按观测记录查询提交历史，按提交时刻与提交标识升序。
     */
    @GetMapping("/api/observations/{observationId}/submissions")
    public List<SubmissionResponse> listByObservation(@PathVariable String observationId) {
        return submissionService.listByObservation(observationId);
    }

    /**
     * 按站点查询提交历史，按提交时刻与提交标识升序。
     */
    @GetMapping("/api/sites/{siteId}/submissions")
    public List<SubmissionResponse> listBySite(@PathVariable String siteId) {
        return submissionService.listBySite(siteId);
    }

    /**
     * 站点提交诊断：各项数量均为实际值。
     */
    @GetMapping("/api/sites/{siteId}/submissions/diagnostics")
    public SubmissionDiagnosticsResponse diagnostics(@PathVariable String siteId) {
        return submissionService.diagnostics(siteId);
    }
}
