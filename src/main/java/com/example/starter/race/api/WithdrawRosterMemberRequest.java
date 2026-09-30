package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 成员退赛请求；不得从已锁定名单中直接删人，只回填退赛生效场次与原因。
 *
 * <p>退赛只影响未封榜的后续计分：已锁定版本固化的成员成绩归属与已封榜团队积分均不重算。
 *
 * @param bib              退赛成员参赛号
 * @param effectiveSession 退赛具体生效场次代码
 * @param reason           退赛原因
 * @param expectedVersion  客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId        全局唯一请求ID（幂等键）
 */
public record WithdrawRosterMemberRequest(
        @NotBlank String bib,
        @NotBlank String effectiveSession,
        @NotBlank @Size(max = 255) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
