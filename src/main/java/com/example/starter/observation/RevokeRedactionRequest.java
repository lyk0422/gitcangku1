package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销附件脱敏请求：仅撤销公开展示资格，脱敏版本、理由与撤销时刻保留。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销原因，随脱敏版本永久保留
 */
public record RevokeRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason) {
}
