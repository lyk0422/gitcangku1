package com.example.starter.workblock.model;

/**
 * 生效施工窗口在某区段上的一条投影（冲突检测与受影响计划查询共用）。
 *
 * @param workKey  施工单业务键
 * @param sectionId 区段 ID
 * @param startUtc 窗口开始时刻（含），UTC 毫秒
 * @param endUtc   窗口结束时刻（不含），UTC 毫秒
 */
public record ActiveWindow(String workKey, String sectionId, long startUtc, long endUtc) {
}
