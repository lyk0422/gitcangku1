package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.OutlierReviewService;

/**
 * 离群复核接口：每条离群记录须两名不同于读数提交人的复核角色一致结论才形成裁决。
 */
@RestController
@RequestMapping("/api/outliers")
public class OutlierController {

    private final OutlierReviewService reviews;

    public OutlierController(OutlierReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 提交复核：201；参数非法 400；离群记录不存在 404；已裁决/复核人冲突/幂等异参 409；
     * 引用版本已变更或证书失效 410（复核标记 STALE）。
     */
    @PostMapping("/{id}/reviews")
    public ResponseEntity<?> review(@PathVariable long id, @RequestBody SubmitReviewRequest request) {
        OutlierReviewService.ReviewOutcome outcome = reviews.review(id, request);
        if (outcome.stale()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(new ErrorResponse("STALE_VERSION", outcome.staleMessage()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.response());
    }
}