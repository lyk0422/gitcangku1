package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 新增闭合应力循环请求。一个循环含升峰、回落两次采样及其引用的现存工时读数版本；
 * 缺回落（fall 为 null）视为未闭合，返回 422 且不写半循环。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，非空
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param cycleId          循环标识，设备内唯一
 * @param rise             升峰采样（峰值应力须大于基准应力）
 * @param fall             回落采样（回落应力须等于基准应力）；为 null 表示缺回落
 */
public record AddCycleRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Long expectedVersion,
        @NotBlank String cycleId,
        @NotNull @Valid Sample rise,
        @Valid Sample fall) {

    /**
     * 单次应力采样及其引用的工时读数版本。
     *
     * @param stressMpa          采样应力（兆帕）
     * @param sampledAt          UTC 采样时刻
     * @param readingId          引用的工时读数标识，须已存在
     * @param readingRevisionNo  引用读数的现存修订号，与当前修订号不一致时返回 409
     */
    public record Sample(
            @NotNull Long stressMpa,
            @NotNull Instant sampledAt,
            @NotBlank String readingId,
            @NotNull @Positive Integer readingRevisionNo) {
    }
}
