package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 单个参与计划在某一阶段（BEFORE/AFTER）的不可变快照视图，稳定排序。
 *
 * @param phase        快照阶段：BEFORE 交换前 / AFTER 交换后
 * @param itemSeq      参与项序号（规范化排序后，从 0 开始）
 * @param scheduleKey  计划业务键
 * @param version      快照时的计划版本
 * @param occupancies  该阶段的占用段（左闭右开 UTC，稳定排序）
 */
public record SwapSnapshotView(String phase, int itemSeq, String scheduleKey, int version,
                               List<OccupancyView> occupancies) {
}
