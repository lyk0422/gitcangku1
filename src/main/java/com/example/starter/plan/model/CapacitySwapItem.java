package com.example.starter.plan.model;

/**
 * 容量交换单参与项（创建时提交内容的不可变留痕）。
 *
 * @param swapId          所属交换单 id
 * @param itemSeq         规范化排序后的序号（按 scheduleKey 升序），从 0 开始
 * @param planId          参与计划 id
 * @param scheduleKey     参与计划业务键
 * @param expectedVersion 提交的期望计划版本
 * @param currentJson     提交的当前占用段 JSON（规范化排序）
 * @param targetJson      提交的目标占用段 JSON（规范化排序）
 */
public record CapacitySwapItem(long swapId, int itemSeq, long planId, String scheduleKey,
                               int expectedVersion, String currentJson, String targetJson) {
}
