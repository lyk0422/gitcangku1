package com.example.starter.calibration.service;

import com.example.starter.calibration.api.ErrorResponse;
import com.example.starter.calibration.api.dto.ReviewResponse;

/**
 * 复核提交结果：正常受理（201）或复核已失效（410，STALE 复核已落库）。
 *
 * @param response   复核响应；失效时为 null
 * @param staleError 失效错误体；正常受理时为 null
 */
public record ReviewOutcome(ReviewResponse response, ErrorResponse staleError) {

    public static ReviewOutcome ok(ReviewResponse response) {
        return new ReviewOutcome(response, null);
    }

    public static ReviewOutcome stale(ErrorResponse error) {
        return new ReviewOutcome(null, error);
    }

    public boolean isStale() {
        return staleError != null;
    }
}