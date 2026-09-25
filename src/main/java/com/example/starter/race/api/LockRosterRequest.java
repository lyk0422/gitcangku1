package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 队长提交名单锁定请求。幂等键为服务端计算的 rosterKey 指纹
 * （含队长、赛事版本、队伍和规范化成员集合），同键同参重放首次结果，失败不占键。
 *
 * @param captainBib      提交锁定的队长参赛号，须与队伍队长一致
 * @param members         锁定名单成员（2~8名，均须具有有效个人报名）
 * @param expectedVersion 客户端所见赛事版本
 */
public record LockRosterRequest(
        @NotBlank String captainBib,
        @NotNull List<@NotBlank String> members,
        @NotNull Integer expectedVersion
) {
}
