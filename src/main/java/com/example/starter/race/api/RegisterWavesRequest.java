package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 分批起跑波次整批登记/修改请求。
 *
 * <p>OPEN 赛事封榜前可登记 1~20 个波次；请求体为该赛事的完整波次集合，
 * 重复提交即整体替换既有波次。每名参赛者最多出现在一个波次中。
 *
 * @param waves           波次定义列表（1~20个）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterWavesRequest(
        @NotEmpty @Valid List<WaveDefinition> waves,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /**
     * 单个波次定义。
     *
     * @param waveKey 波次唯一键，同一赛事内唯一
     * @param startAt 波次UTC起跑时刻，Unix毫秒时间戳
     * @param runners 参赛者参赛号集合，可为空；同一参赛者只能属于一个波次
     */
    public record WaveDefinition(
            @NotBlank String waveKey,
            @NotNull @Positive Long startAt,
            @NotNull List<@NotBlank String> runners
    ) {
    }
}
