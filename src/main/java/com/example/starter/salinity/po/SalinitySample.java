package com.example.starter.salinity.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 盐度样本持久化对象（原始样本与历次重测各一行，永不覆盖删除）。
 *
 * @param id             自增主键
 * @param voyageCode     航次编号，非空
 * @param stationCode    站位编号，非空
 * @param depthM         采样深度，单位米，合法范围 0 至 500（含边界）
 * @param salinityPsu    盐度，单位 PSU（实用盐度单位，无量纲），合法范围 0 至 45（含边界）
 * @param collectedUtc   采集 UTC 时刻，非空
 * @param sampleNo       采样编号，全局唯一，非空
 * @param requestId      提交请求 ID，用于幂等重放；未携带时为 {@code null}，非空时全局唯一
 * @param adopted        是否为所在站位深度的当前采纳版本：{@code true} 是，{@code false} 否
 * @param supersededBy   被哪条重测样本替换（其主键）；未被替换为 {@code null}
 * @param createdUtc     入库 UTC 时刻，非空
 */
public record SalinitySample(
        Long id,
        String voyageCode,
        String stationCode,
        Integer depthM,
        BigDecimal salinityPsu,
        Instant collectedUtc,
        String sampleNo,
        String requestId,
        boolean adopted,
        Long supersededBy,
        Instant createdUtc
) {
}
