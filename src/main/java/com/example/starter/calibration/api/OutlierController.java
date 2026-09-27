package com.example.starter.calibration.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.service.BatchService;
import com.example.starter.calibration.service.IdempotentResponse;
import com.example.starter.calibration.service.ReviewService;

/**
 * 离群记录接口：复核提交（法定人数裁决）与明细查询。
 */
@RestController
@RequestMapping("/api/outliers")
public class OutlierController {

    private final ReviewService reviews;
    private final BatchService batches;

    public OutlierController(ReviewService reviews, BatchService batches) {
        this.reviews = reviews;
        this.batches = batches;
    }

    /**
     * 提交复核：201；同 requestId 同参重放首次结果，异参 409；
     * 版本已变更或证书失效 410 并留痕 STALE；复核人=提交人 403；
     * 重复复核或已有最终裁决 409。
     */
    @PostMapping("/{outlierId}/reviews")
    public ResponseEntity<String> review(@PathVariable long outlierId,
                                         @RequestBody ReviewRequest request) {
        IdempotentResponse response = reviews.review(outlierId, request);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    /**
     * 离群明细：封存证据、实际/允许偏差与差额、裁决与全部复核；不存在 404。只读。
     */
    @GetMapping("/{outlierId}")
    public OutlierResponse detail(@PathVariable long outlierId) {
        return batches.outlierDetail(outlierId);
    }
}
