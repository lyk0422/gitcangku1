package com.example.starter.permit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 许可撤销请求：撤销后许可失效，撤销时刻未合并观测被标记 PERMIT_REVIEW 并写入快照。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record RevokePermitRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
