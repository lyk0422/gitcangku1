package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 许可撤销请求：许可进入 REVOKED 终态，其下未合并观测进入 PERMIT_REVIEW 并写入复核快照。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销原因，写入每条受影响观测的复核快照
 */
public record RevokePermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Size(max = 1024) String reason) {
}
