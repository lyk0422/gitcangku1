package com.example.starter.api;

import com.example.starter.api.dto.CreateReviewRequest;
import com.example.starter.api.dto.CurrentReviewResponse;
import com.example.starter.api.dto.ReviewResponse;
import com.example.starter.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核接口：提交审核、查询历史结果、查询航线当前可用结论。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 提交审核：航线版本与空域版本须均为当前版本，否则返回 409。
     */
    @PostMapping("/reviews")
    public ReviewResponse create(@Valid @RequestBody CreateReviewRequest request) {
        return reviewService.create(request);
    }

    /**
     * 查询历史审核结果：永远返回提交时的原结论。
     */
    @GetMapping("/reviews/{reviewId}")
    public ReviewResponse getById(@PathVariable String reviewId) {
        return reviewService.getById(reviewId);
    }

    /**
     * 查询航线当前可用结论：任一相关版本不再匹配即返回 STALE。
     */
    @GetMapping("/routes/{routeId}/current-review")
    public CurrentReviewResponse currentForRoute(@PathVariable String routeId) {
        return reviewService.currentForRoute(routeId);
    }
}
