package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.ReviewOutcome;
import com.example.starter.calibration.service.ReviewService;

/**
 * 离群复核接口。
 */
@RestController
@RequestMapping("/api/outliers")
public class OutlierController {

    private final ReviewService reviews;

    public OutlierController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 提交复核：201 受理；410 复核引用的版本已变更或证书失效（复核落库为 STALE）；
     * 400 参数非法；404 离群不存在；409 已裁决/复核人即提交人/重复复核。
     */
    @PostMapping("/{outlierId}/reviews")
    public ResponseEntity<?> review(@PathVariable long outlierId,
                                    @RequestBody SubmitReviewRequest request) {
        ReviewOutcome outcome = reviews.submit(outlierId, request);
        if (outcome.isStale()) {
            return ResponseEntity.status(HttpStatus.GONE).body(outcome.staleError());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.response());
    }
}