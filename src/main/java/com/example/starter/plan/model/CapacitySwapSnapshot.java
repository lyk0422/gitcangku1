package com.example.starter.plan.model;

/**
 * 交换前后不可变快照记录。
 *
 * @param id               主键
 * @param swapId           所属交换单 id
 * @param phase            快照阶段：BEFORE 交换前 / AFTER 交换后
 * @param itemSeq          参与项序号（与 rail_capacity_swap_item.item_seq 一致）
 * @param planId           参与计划 id
 * @param scheduleKey      参与计划业务键
 * @param version          快照时的计划版本
 * @param occupanciesJson  占用段快照 JSON（规范化排序）
 * @param createdAt        快照写入时刻，UTC 毫秒
 */
public record CapacitySwapSnapshot(long id, long swapId, String phase, int itemSeq, long planId,
                                   String scheduleKey, int version, String occupanciesJson,
                                   long createdAt) {
}
