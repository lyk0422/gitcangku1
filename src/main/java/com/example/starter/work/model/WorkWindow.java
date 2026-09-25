package com.example.starter.work.model;

import java.time.Instant;

/**
 * 生效施工窗口在单个区段上的投影（施工单与区段关联的联接行），区间左闭右开。
 *
 * @param workKey   施工单业务键
 * @param sectionId 区段 ID
 * @param startUtc  窗口开始时刻（含），UTC
 * @param endUtc    窗口结束时刻（不含），UTC
 */
public record WorkWindow(String workKey, String sectionId, Instant startUtc, Instant endUtc) {
}
