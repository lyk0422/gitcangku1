package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分批起跑波次登记请求：一次性登记 1~20 个波次。
 *
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param waves           波次定义列表（1~20 个），每个含唯一 waveKey、UTC 起跑时刻与参赛者集合
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterWavesRequest(
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 20) @Valid List<WaveDefinition> waves,
        @NotBlank String requestId
) {

    /**
     * 单个波次定义。
     *
     * @param waveKey 波次唯一键，同一次登记内与赛事内均唯一
     * @param startMs 波次 UTC 起跑时刻，Unix 毫秒时间戳；不同波次可相同
     * @param bibs    参赛者参赛号集合；同一参赛者只能属于一个波次
     */
    public record WaveDefinition(
            @NotBlank String waveKey,
            @NotNull @Positive Long startMs,
            @NotNull List<@NotBlank String> bibs
    ) {
    }
}
