package com.example.starter.batch.dto;

import com.example.starter.batch.DeviationStatus;
import com.example.starter.batch.DeviationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 仓储温度偏差事件响应。
 * 温度与限值快照单位均为摄氏度(℃)，时刻均为 UTC。
 *
 * @param observedTemperatureCelsius 触发读数温度；缺测偏差取间隔后一读数温度
 * @param upperLimitSnapshot         触发时段上限快照，限值后续修改不回写
 * @param lowerLimitSnapshot         触发时段下限快照，限值后续修改不回写
 * @param gapStart                   缺测区间起点（前一读数时刻），越界偏差为 null
 * @param gapEnd                     缺测区间终点（间隔大于30分钟的后一读数时刻），越界偏差为 null
 * @param disposedAt                 解除时刻，未处置为 null
 * @param confirmations              处置确认列表（处置人、角色、备注、时刻）
 */
public record DeviationResponse(
        String deviationKey,
        String batchKey,
        String segmentKey,
        DeviationType type,
        DeviationStatus status,
        int version,
        BigDecimal observedTemperatureCelsius,
        BigDecimal upperLimitSnapshot,
        BigDecimal lowerLimitSnapshot,
        Instant gapStart,
        Instant gapEnd,
        Instant createdAt,
        Instant disposedAt,
        List<ConfirmationResponse> confirmations
) {
}
