package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 拒绝重分申请请求；拒绝只改变申请状态，不改变任何成绩。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RejectReclassRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}