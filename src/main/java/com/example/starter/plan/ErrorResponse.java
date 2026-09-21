package com.example.starter.plan;

import java.util.List;

/**
 * 统一错误响应体。
 *
 * @param code 稳定错误码，用于区分 400/404/409/422 的具体原因
 * @param message 人类可读的错误描述
 * @param conflicts 时隙冲突明细，仅 422 时非空
 */
public record ErrorResponse(String code, String message, List<ConflictDetail> conflicts) {
}
