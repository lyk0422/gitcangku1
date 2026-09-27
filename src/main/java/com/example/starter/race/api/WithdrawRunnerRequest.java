package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 选手退赛请求。退赛为资格终态：退赛后不可登记/恢复医疗暂停、不可提交分段计时、不可修订计时，
 * 已保存的原始计时与分段证据不删除。
 *
 * @param reason          退赛原因；可空
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @Size(max = 512) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
