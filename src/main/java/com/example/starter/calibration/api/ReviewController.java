package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.GateStatusResponse;
import com.example.starter.calibration.api.dto.PendingReviewResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.service.ReviewOutcome;
import com.example.starter.calibration.service.ReviewService;

/**
 * 同行复核接口：提交复核、复核历史、待复核清单、放行门禁状态。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 提交同行复核：201 有效；版本变化 410（复核标为 STALE）；
     * 重复同类有效复核 409；复核人即提交人/证书撤销 422；参数非法 400。
     * 复核人通过 X-Actor-Id 提供，幂等键通过 X-Request-Id 提供。
     */
    @PostMapping("/reviews")
    public ResponseEntity<?> submit(@RequestBody SubmitReviewRequest request,
                                    @RequestHeader("X-Actor-Id") String actor,
                                    @RequestHeader("X-Request-Id") String requestId) {
        ReviewOutcome outcome = reviews.submit(request, actor, requestId);
        if (outcome.status() == 410) {
            return ResponseEntity.status(410).body(new ErrorResponse(outcome.code(), outcome.message()));
        }
        return ResponseEntity.status(201).body(outcome.review());
    }

    /**
     * 复核历史：某测量全部版本的复核记录（含 VALID/STALE）。
     */
    @GetMapping("/measurements/{key}/reviews")
    public List<ReviewResponse> history(@PathVariable String key) {
        return reviews.history(key);
    }

    /**
     * 待复核清单：当前版本等待有效 PASS 复核的测量，可按仪器过滤。
     */
    @GetMapping("/reviews/pending")
    public List<PendingReviewResponse> pending(@RequestParam(required = false) String instrumentId) {
        return reviews.pending(instrumentId);
    }

    /**
     * 放行门禁状态：既有判定条件与有效 PASS 复核门禁的聚合结果。
     */
    @GetMapping("/measurements/{key}/release-gate")
    public GateStatusResponse gate(@PathVariable String key) {
        return reviews.gate(key);
    }
}
