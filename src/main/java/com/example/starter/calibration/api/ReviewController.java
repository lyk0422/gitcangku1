package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchDiffResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReReleaseRequest;
import com.example.starter.calibration.api.dto.ReReleaseResponse;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.RevisionChainResponse;
import com.example.starter.calibration.service.QueryService;
import com.example.starter.calibration.service.ReReleaseService;
import com.example.starter.calibration.service.ReviewService;
import com.example.starter.calibration.service.RevisionService;

/**
 * 放行后复核、后继修订、重新放行与只读查询接口。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviews;
    private final RevisionService revisions;
    private final ReReleaseService reReleases;
    private final QueryService queries;

    public ReviewController(ReviewService reviews,
                            RevisionService revisions,
                            ReReleaseService reReleases,
                            QueryService queries) {
        this.reviews = reviews;
        this.revisions = revisions;
        this.reReleases = reReleases;
        this.queries = queries;
    }

    /**
     * 复核驳回：200；批次不存在 404；批次状态不允许复核 409；复核人即原放行人 403；
     * 驳回项位置越界或版本变化整批失败 409；reviewKey 异参 409，同参换序重放返回原结果。
     */
    @PostMapping("/batches/{batchId}/review")
    public ReviewResponse review(@PathVariable String batchId,
                                 @RequestBody ReviewRequest request,
                                 @RequestHeader("X-Actor-Id") String actor) {
        return reviews.review(batchId, request, actor);
    }

    /**
     * 创建后继修订：201；测量不存在 404；非 REJECTED 409；非原提交人 403；已存在后继修订 409。
     */
    @PostMapping("/revisions")
    public ResponseEntity<MeasurementResponse> createRevision(
            @RequestBody CreateRevisionRequest request,
            @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(revisions.create(request, actor));
    }

    /**
     * 重新放行：200；批次不存在 404；批次状态不允许 409；映射缺漏/重复/证书失效/值越界/版本变化整批失败 409；
     * requestId 异参 409，同参换序重放返回原结果。
     */
    @PostMapping("/batches/{batchId}/re-release")
    public ReReleaseResponse reRelease(@PathVariable String batchId,
                                       @RequestBody ReReleaseRequest request,
                                       @RequestHeader("X-Actor-Id") String actor) {
        return reReleases.reRelease(batchId, request, actor);
    }

    /**
     * 批次详情（只读）：200；批次不存在 404。
     */
    @GetMapping("/batches/{batchId}")
    public BatchResponse batch(@PathVariable String batchId) {
        return queries.batch(batchId);
    }

    /**
     * 批次差异（只读）：200；批次不存在或尚未被重新放行 404。
     */
    @GetMapping("/batches/{batchId}/diff")
    public BatchDiffResponse diff(@PathVariable String batchId) {
        return queries.diff(batchId);
    }

    /**
     * 修订链（只读）：200；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/revisions")
    public RevisionChainResponse revisionChain(@PathVariable String key) {
        return queries.revisionChain(key);
    }
}
