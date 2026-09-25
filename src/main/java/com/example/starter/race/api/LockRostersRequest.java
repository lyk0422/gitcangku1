package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量名单锁定请求；任一队伍校验失败整批 422 且不写入，
 * 成功后在一个事务内写入全部锁定快照。
 *
 * @param locks           各队伍的锁定请求（至少一个）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）；rosterKey 指纹含队长、赛事版本、
 *                        队伍与规范化成员集合，同键同参重放首次结果，失败不占键
 */
public record LockRostersRequest(
        @NotEmpty List<@Valid TeamLockRequest> locks,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /**
     * 单支队伍的锁定请求。
     *
     * @param teamId     队伍ID
     * @param captainBib 队长参赛号，须与队伍登记队长一致且在成员名单中
     * @param members    锁定名单成员参赛号（含队长，2~8人，全部须已报名）
     */
    public record TeamLockRequest(
            @NotBlank String teamId,
            @NotBlank String captainBib,
            @NotEmpty List<@NotBlank String> members
    ) {
    }
}
