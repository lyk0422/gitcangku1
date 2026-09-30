package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建申诉请求：创建时按赛事当前保证金配置冻结该金额，作为本申诉的金额快照。
 * 余额不足时不创建申诉、不冻结、不锁成绩。
 *
 * @param protestId       申诉ID，全局唯一
 * @param accountId       提交申诉并被冻结保证金的账户ID
 * @param reason          申诉理由，非空，最长512字符
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateProtestRequest(
        @NotBlank String protestId,
        @NotBlank String accountId,
        @NotBlank @Size(max = 512) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
