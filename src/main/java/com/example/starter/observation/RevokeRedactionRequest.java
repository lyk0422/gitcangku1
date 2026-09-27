package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏版本公开展示资格请求：仅撤销公开展示，保留脱敏版本、替换说明与撤销时刻。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销理由
 */
public record RevokeRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason) {
}
