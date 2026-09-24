package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 晋级名单生成请求：在一个事务内按当前一致状态选出每组前 Q 名直接晋级，
 * 再从各组未直接晋级者中全局取前 W 名补位；并列跨过边界时全部纳入。
 *
 * @param advancementKey  晋级名单键，全局唯一，撤销后也不可复用
 * @param directQuota     每组直接晋级数 Q（1~8）
 * @param wildcardQuota   跨组补位名额 W（0~8）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键：同参重放首次结果，异参409，失败不占键）
 */
public record GenerateAdvancementRequest(
        @NotBlank String advancementKey,
        @Min(1) @Max(8) Integer directQuota,
        @Min(0) @Max(8) Integer wildcardQuota,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
