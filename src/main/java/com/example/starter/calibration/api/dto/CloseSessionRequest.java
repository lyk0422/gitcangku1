package com.example.starter.calibration.api.dto;

/**
 * 关闭会话请求。关闭冻结全部方向与精确结果，之后不可再追加。
 *
 * @param requestId              幂等请求键，全局唯一
 * @param expectedSessionVersion 期望会话版本（等于当前已追加条目数），不一致返回 409
 * @param closedBy               关闭人
 */
public record CloseSessionRequest(
        String requestId,
        Long expectedSessionVersion,
        String closedBy) {
}
