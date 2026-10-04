package com.example.starter.snow;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.snow.SnowDtos.AreaStatusResponse;
import com.example.starter.snow.SnowDtos.ConfirmationRequest;
import com.example.starter.snow.SnowDtos.CorrectionRequest;
import com.example.starter.snow.SnowDtos.CorrectionResponse;
import com.example.starter.snow.SnowDtos.ReadingRequest;
import com.example.starter.snow.SnowDtos.ReadingResponse;
import com.example.starter.snow.SnowDtos.ReviewCreateRequest;
import com.example.starter.snow.SnowDtos.ReviewResponse;
import com.example.starter.snow.SnowDtos.ServiceResult;

/**
 * 雪崩控制区接口：合成雪况记录、勘误、解除审核与状态查询；不提供真实安全预报。
 */
@RestController
@RequestMapping("/api/areas/{areaId}")
public class SnowController {

    private final SnowService service;

    public SnowController(SnowService service) {
        this.service = service;
    }

    /** 上报整点雪况读数（幂等）。 */
    @PostMapping("/readings")
    public ResponseEntity<ReadingResponse> submitReading(@PathVariable String areaId,
            @Valid @RequestBody ReadingRequest req) {
        ServiceResult<ReadingResponse> result = service.submitReading(areaId, req);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 对指定整点旧读数追加勘误（幂等）。 */
    @PostMapping("/readings/{hourUtc}/corrections")
    public ResponseEntity<CorrectionResponse> submitCorrection(@PathVariable String areaId,
            @PathVariable String hourUtc, @Valid @RequestBody CorrectionRequest req) {
        ServiceResult<CorrectionResponse> result = service.submitCorrection(areaId, hourUtc, req);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 创建解除审核单（幂等），创建人即第一名确认人。 */
    @PostMapping("/lift-reviews")
    public ResponseEntity<ReviewResponse> createReview(@PathVariable String areaId,
            @Valid @RequestBody ReviewCreateRequest req) {
        ServiceResult<ReviewResponse> result = service.createReview(areaId, req);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 第二名审核员确认（幂等），确认后生成解除版本。 */
    @PostMapping("/lift-reviews/{reviewId}/confirmations")
    public ResponseEntity<ReviewResponse> confirm(@PathVariable String areaId, @PathVariable long reviewId,
            @Valid @RequestBody ConfirmationRequest req) {
        ServiceResult<ReviewResponse> result = service.confirm(areaId, reviewId, req);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 查询区域状态与时间线。 */
    @GetMapping("/status")
    public AreaStatusResponse getStatus(@PathVariable String areaId) {
        return service.getStatus(areaId);
    }
}