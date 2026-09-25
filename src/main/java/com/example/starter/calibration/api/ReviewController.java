package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.GateStatusResponse;
import com.example.starter.calibration.api.dto.PendingReviewItem;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.ReviewService;

/**
 * 同行复核接口：提交复核、复核历史、待复核清单与放行门禁状态查询。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 提交同行复核：201；复核人通过 X-Actor-Id 提供且须不同于提交人。
     * 参数非法 400；测量不存在 404；重复同类有效复核 / 已放行 / 请求 ID 冲突 409；
     * 被复核版本已过期 410（复核记为 STALE）；复核人即提交人 / 证书已撤销 422。
     * requestId 同键同参重放首次结果，同键异参 409，失败不占键。
     */
    @PostMapping("/measurements/{key}/reviews")
    public ResponseEntity<ReviewResponse> submit(@PathVariable String key,
                                                 @RequestBody SubmitReviewRequest request,
                                                 @RequestHeader("X-Actor-Id") String actor) {
        ReviewService.ReviewOutcome outcome = reviews.submit(key, request, actor);
        return ResponseEntity.status(outcome.stale() ? HttpStatus.GONE : HttpStatus.CREATED)
                .body(outcome.response());
    }

    /**
     * 复核历史：包含全部版本与 STALE 记录；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/reviews")
    public List<ReviewResponse> history(@PathVariable String key) {
        return reviews.history(key);
    }

    /**
     * 放行门禁状态：既有判定条件与复核门禁的当前评估；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/release-gate")
    public GateStatusResponse gateStatus(@PathVariable String key) {
        return reviews.gateStatus(key);
    }

    /**
     * 待复核清单：待放行且当前修订版本尚无有效 PASS 复核的测量。
     */
    @GetMapping("/reviews/pending")
    public List<PendingReviewItem> pending() {
        return reviews.pending();
    }
}
