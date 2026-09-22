package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交选手检查点通过记录请求；记录允许乱序到达，
 * 但按检查点顺序查看时耗时必须严格递增（违反返回422且不写入）。
 *
 * @param checkpointCode  检查点编码，必须属于该赛事已配置的检查点
 * @param elapsedMillis   分段耗时（毫秒，1~86400000），必须小于该选手已有原始完赛耗时
 * @param expectedVersion 客户端所见赛事版本
 * @param timingId        全局唯一分段计时ID（业务幂等键：同参重放原结果，异参409）
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RecordSplitRequest(
        @NotBlank String checkpointCode,
        @NotNull @Positive Long elapsedMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String timingId,
        @NotBlank String requestId
) {
}
