package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏请求：仅撤销指定脱敏版本的公开展示资格，版本、理由与撤销时刻全部保留。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销理由
 * @param operator  执行撤销的操作者标识
 */
public record RevokeRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String operator) {
}
