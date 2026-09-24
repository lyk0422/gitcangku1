package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 夜间计划对联合发布请求。
 *
 * <p>按 nightPairKey 找到当日与次日两张草稿，在同一事务内重查版本与冲突后同时发布；
 * 任一张版本不符、状态不符或时隙冲突，两张都保持草稿。requestKey 为幂等键。
 *
 * @param nightPairKey          计划对业务键，两张草稿创建时声明的同一键
 * @param sameDayScheduleKey    当日（跨零点起始运营日）草稿业务键
 * @param nextDayScheduleKey    次日草稿业务键
 * @param expectedSameDayVersion 当日草稿期望版本
 * @param expectedNextDayVersion 次日草稿期望版本
 */
public record PairPublishRequest(
        @NotBlank String requestKey,
        @NotBlank String nightPairKey,
        @NotBlank String sameDayScheduleKey,
        @NotBlank String nextDayScheduleKey,
        @NotNull Integer expectedSameDayVersion,
        @NotNull Integer expectedNextDayVersion) {
}
