package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 退赛登记请求。
 *
 * @param bib                 退赛选手参赛号
 * @param status              退赛状态：DNS-未出发，DNF-中途退赛
 * @param reason              退赛原因，非空
 * @param lastCheckpointCode  DNF 最后通过的检查点代码（须为该选手已有分段中顺序最大者）；DNS 不传
 * @param expectedVersion     客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId           全局唯一请求ID（幂等键）
 * @param withdrawalKey       全局唯一退赛登记键（第二层幂等键）
 */
public record RegisterWithdrawalRequest(
        @NotBlank String bib,
        @NotBlank String status,
        @NotBlank String reason,
        String lastCheckpointCode,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String withdrawalKey
) {
}
