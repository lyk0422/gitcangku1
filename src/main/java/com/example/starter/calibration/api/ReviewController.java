package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchDiffResponse;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.service.ReviewService;

/**
 * 复核接口：放行后复核驳回（原子生效）与批次差异只读查询。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 提交复核驳回：200；参数非法 400；批次不存在 404；
     * 批次状态冲突/复核人即原放行人/驳回项不满足条件/reviewKey 异参 409。
     * 复核人通过 X-Actor-Id 请求头提供，不能是原放行人。
     */
    @PostMapping("/reviews")
    public ReviewResponse review(@RequestBody ReviewRequest request,
                                 @RequestHeader("X-Actor-Id") String actor) {
        return reviews.review(request, actor);
    }

    /**
     * 批次差异只读查询：批次状态、复核信息及每个位置的驳回差异；批次不存在 404。
     */
    @GetMapping("/batches/{batchId}/diff")
    public BatchDiffResponse diff(@PathVariable String batchId) {
        return reviews.diff(batchId);
    }
}
