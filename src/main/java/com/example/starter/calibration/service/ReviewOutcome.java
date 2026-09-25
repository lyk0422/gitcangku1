package com.example.starter.calibration.service;

import com.example.starter.calibration.api.dto.ReviewResponse;

/**
 * 复核提交结果。410（版本失效）需要提交 STALE 记录，故不通过异常回滚，而以本类型携带状态码返回。
 *
 * @param status   HTTP 状态码：201 复核有效提交 / 410 版本已变化（复核标为 STALE）
 * @param review   有效提交时的复核响应；410 时为 null
 * @param code     410 时的业务错误码
 * @param message  410 时的错误描述
 */
public record ReviewOutcome(int status, ReviewResponse review, String code, String message) {

    static ReviewOutcome created(ReviewResponse review) {
        return new ReviewOutcome(201, review, null, null);
    }

    static ReviewOutcome stale(String code, String message) {
        return new ReviewOutcome(410, null, code, message);
    }
}
