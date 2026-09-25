package com.example.starter.work.model;

import java.time.Instant;

/**
 * 铁路施工占用窗口施工单主记录，窗口区间左闭右开 [startUtc, endUtc)。
 *
 * @param id        主键
 * @param workKey   施工单业务键，全局唯一
 * @param version   施工单版本，修改成功一次加一
 * @param status    施工单状态
 * @param operator  最近操作者
 * @param startUtc  占用开始时刻（含），UTC
 * @param endUtc    占用结束时刻（不含），UTC，必须晚于 startUtc
 */
public record WorkOrder(long id, String workKey, int version, WorkStatus status, String operator,
                        Instant startUtc, Instant endUtc) {
}
