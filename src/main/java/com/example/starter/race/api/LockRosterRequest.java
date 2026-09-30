package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 团队名单锁定请求：封榜前提交一个完整名单版本。
 *
 * <p>成员（参赛号）必须全部已在赛事中登记；同一请求内不得重复；
 * 成员在同一赛事中只能归属一个队；后续版本必须包含上一版本全部成员（只增不换）。
 *
 * @param members         本次锁定版本的完整成员参赛号列表（顺序不影响语义，服务端按参赛号字典序固化）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）；同键换名单返回409
 */
public record LockRosterRequest(
        @NotEmpty List<@NotBlank String> members,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
