package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 本轮扣减明细视图：最近保养锚点之后全部生效停机区间的扣减量及合计。
 *
 * @param equipmentId             设备唯一标识
 * @param version                 设备当前版本号
 * @param anchorSampledAt         最近保养锚点时刻（无保养时为 null，此时统计全部生效区间）
 * @param totalDeductionMinutes   本轮扣减合计（分钟）
 * @param items                   参与本轮扣减的生效停机区间明细（按开始时刻升序）
 */
public record DeductionsResponse(
        String equipmentId,
        long version,
        Instant anchorSampledAt,
        long totalDeductionMinutes,
        List<Item> items) {

    /**
     * 单条生效停机区间的扣减明细。
     *
     * @param downtimeKey       停机业务键
     * @param startAt           停机开始时刻（UTC，含）
     * @param endAt             停机结束时刻（UTC，不含）
     * @param deductionMinutes  该区间当前扣减量（分钟）
     */
    public record Item(String downtimeKey, Instant startAt, Instant endAt, long deductionMinutes) {
    }
}
