package com.example.starter.curtailment.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 功率采样实体，对应表 power_sample。每 15 分钟一个刻度；迟到更正以递增版本追加，原始采样保留。
 *
 * @param id            主键
 * @param instructionId 所属限发指令 id
 * @param unitId        机组编号
 * @param tickStart     刻度起点（UTC），15 分钟对齐且落在指令区间内
 * @param avgPowerKw    该刻度 15 分钟平均功率，单位 kW，非负
 * @param sampleVersion 采样版本：首次上报为 1，每次迟到更正递增
 * @param requestId     产生该版本的请求标识
 * @param createdAt     写入时间（UTC）
 */
public record PowerSample(long id, long instructionId, String unitId, Instant tickStart, BigDecimal avgPowerKw,
                          int sampleVersion, String requestId, Instant createdAt) {
}