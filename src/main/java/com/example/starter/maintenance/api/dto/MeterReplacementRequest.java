package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 工时表更换请求：关闭当前 ACTIVE 表并启用新表，冻结新表 offset 使虚拟工时连续。
 * 链式连续性以旧表最后有效读数为准：新表 offset = virtual(旧表最后有效读数) - initialRawHours；
 * finalRawHours 为旧表申报封顶值，仅约束关闭后的修订，不进入 offset 公式。
 *
 * @param requestId              全局唯一请求标识（幂等键）；同参重放首次完整链快照，异参 409，失败不占键
 * @param expectedVersion        设备期望版本号，与当前版本不一致时返回 409
 * @param replacementKey         更换记录唯一标识（全局唯一）
 * @param newMeterKey            新表唯一标识（全局唯一，保证更换链不可成环）
 * @param oldLastReadingVersion  旧表最后一条读数的当前修订号，不一致时返回 409
 * @param finalRawHours          旧表申报最终原始读数（分钟），非负且不得小于旧表最后有效读数
 * @param initialRawHours        新表起始原始读数（分钟），非负
 */
public record MeterReplacementRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String replacementKey,
        @NotBlank String newMeterKey,
        @NotNull @Positive Integer oldLastReadingVersion,
        @NotNull @PositiveOrZero Long finalRawHours,
        @NotNull @PositiveOrZero Long initialRawHours) {
}
