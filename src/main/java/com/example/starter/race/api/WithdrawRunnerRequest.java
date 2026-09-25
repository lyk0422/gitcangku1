package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 退赛登记请求：为选手登记 DNS（未出发）或 DNF（中途退赛）。
 *
 * <p>DNS 要求该选手尚无任何分段记录与完赛计时；
 * DNF 要求至少一条分段记录、尚无完赛计时，并指定最后通过的检查点
 * （须是其已有记录中顺序最大者）。
 *
 * @param withdrawalKey      全局唯一退赛键；同状态重复登记按幂等返回首次结果，撤销须携带同一键
 * @param status             退赛状态：DNS / DNF
 * @param reason             非空退赛原因
 * @param lastCheckpointCode DNF 最后通过的检查点代码；DNS 必须为空
 * @param expectedVersion    客户端所见赛事版本
 * @param requestId          全局唯一请求ID（写操作幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawRunnerRequest(
        @NotBlank String withdrawalKey,
        @NotBlank String status,
        @NotBlank @Size(max = 512) String reason,
        String lastCheckpointCode,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
