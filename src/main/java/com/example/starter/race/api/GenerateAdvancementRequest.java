package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 生成晋级名单请求；在一个事务内读取一致状态并原子写入不可变快照。
 *
 * @param advancementKey 晋级名单键，全局唯一；撤销后键不复用
 * @param quotaPerGroup  每组直接晋级名额 Q（1~8）
 * @param wildcardCount  全局补位名额 W（0~8）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId      全局唯一请求ID（幂等键）
 */
public record GenerateAdvancementRequest(
        @NotBlank String advancementKey,
        @NotNull Integer quotaPerGroup,
        @NotNull Integer wildcardCount,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
