package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 拒绝重分申请请求；拒绝不改变任何成绩。
 *
 * @param reason          拒绝理由（1~512字符）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RejectReclassRequest(
        @NotBlank @Size(max = 512) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
