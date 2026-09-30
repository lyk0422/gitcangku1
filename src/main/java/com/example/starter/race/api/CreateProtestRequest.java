package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建成绩申诉请求；创建时按赛事当前保证金配置冻结等额余额并固化为该申诉快照。
 * 参赛号以路径参数为准。
 *
 * @param protestId       申诉ID，全局唯一
 * @param reason          申诉理由（非空，最长500字）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateProtestRequest(
        @NotBlank String protestId,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
