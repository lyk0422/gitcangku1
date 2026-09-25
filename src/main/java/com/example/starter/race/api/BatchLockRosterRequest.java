package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量锁定多支队伍名单请求。先整批校验（成员不跨队、人数2~8、全部个人报名有效），
 * 任一失败整批422且不写入；成功后在一事务写入所有锁定快照。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param teams           待锁定的队伍名单（至少一支）
 */
public record BatchLockRosterRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotEmpty List<@Valid TeamLockEntry> teams
) {

    /**
     * 单支队伍的锁定名单。
     *
     * @param teamId     队伍ID
     * @param captainBib 提交锁定的队长参赛号，须与队伍队长一致
     * @param members    锁定名单成员（2~8名，均须具有有效个人报名）
     */
    public record TeamLockEntry(
            @NotBlank String teamId,
            @NotBlank String captainBib,
            @NotNull List<@NotBlank String> members
    ) {
    }
}
