package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;

/**
 * 复核失效（410）：复核引用的测量修订版本或证书版本已变更/失效。
 * 抛出前已写入 stale 复核留痕记录，事务不得回滚该留痕。
 */
public class StaleReviewException extends ApiException {

    public StaleReviewException(String message) {
        super(HttpStatus.GONE, "STALE_REVIEW", message);
    }
}
