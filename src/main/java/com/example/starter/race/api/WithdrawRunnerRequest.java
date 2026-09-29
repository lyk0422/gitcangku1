package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 选手退赛请求；退赛后不得再提交读数或更换芯片，榜单状态为 WITHDRAWN。
 *
 * @param referee         退赛操作裁判（操作者账号），参与幂等指纹
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotBlank @Size(max = 128) String referee,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
