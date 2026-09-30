package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 锁定团队名单版本请求；封榜前提交。
 *
 * <p>名单内成员不得为空、不得重复，且每名成员在同一赛事内只能归属一个团队；
 * 任一名单项不合法时整单拒绝，不生成半份名单，也不改变已有成绩归属。
 *
 * @param members         参赛号列表（赛事内已登记选手；不得重复）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）；同键重放首次成员与成绩快照，换名单返回409
 */
public record LockRosterRequest(
        @NotEmpty List<@NotBlank String> members,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
