package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接收方承诺请求：接收方就某个授权代次声明用途与 UTC 到期时刻。
 *
 * @param requestId   幂等请求标识，同一 requestId 重放首次承诺；换用途或到期时刻返回 409
 * @param recipientKey 接收方标识（合成字符串）
 * @param subjectKey  授权主体标识（合成字符串）
 * @param purpose     承诺用途，必须与读取时声明的用途一致，如 AUDIT 仅供审计使用
 * @param epoch       所承诺的授权代次，从 1 开始
 * @param expiresAt   UTC 到期时刻，必须晚于提交时刻；到期时刻恰好到达承诺即失效
 */
public record AttestationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotNull @Future Instant expiresAt) {
}
