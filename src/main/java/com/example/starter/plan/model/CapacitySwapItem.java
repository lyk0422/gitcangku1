package com.example.starter.plan.model;

/**
 * 交换单项（参与计划）记录。
 *
 * @param id              主键
 * @param swapId          所属交换单 id
 * @param itemSeq         稳定排序序号，从 0 开始，按计划业务键升序
 * @param planId          参与交换的计划 id
 * @param scheduleKey     参与交换的计划业务键
 * @param expectedVersion 预览提交的期望版本
 * @param finalVersion    激活成功后的计划版本；未激活为 null
 */
public record CapacitySwapItem(long id, long swapId, int itemSeq, long planId, String scheduleKey,
                               int expectedVersion, Integer finalVersion) {
}
