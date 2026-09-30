package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 成员退赛请求：记录退赛生效的具体场次与原因，不从已锁定名单中删人。
 *
 * <p>退赛不推进赛事版本，封榜前后均可登记；封榜快照是否排除该成员取决于
 * 生效场次是否不晚于封榜场次，已封榜成绩永不重算。
 *
 * @param effectiveSession 退赛生效场次号，从1开始的正整数；该场次及之后的未封榜计分排除该成员
 * @param reason           退赛原因，非空，最长512字符
 * @param requestId        全局唯一请求ID（幂等键）
 */
public record WithdrawMemberRequest(
        @Positive @NotNull Integer effectiveSession,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank String requestId
) {
}
