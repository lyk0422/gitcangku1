package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 容量交换单创建（预览）/激活结果。
 *
 * @param swapKey       交换单业务键
 * @param opDate        运营日
 * @param status        交换单状态：PREVIEW 仅预览 / ACTIVE 已激活
 * @param plans         全部参与计划的版本与占用（预览为交换前实际占用；激活为交换后占用）
 * @param conflicts     按完整后态计算的冲突列表（稳定排序）
 * @param beforeSnapshots 交换前计划快照（创建时为空；激活后查询/响应中返回不可变快照）
 * @param afterSnapshots  交换后计划快照（创建时为空；激活后返回不可变快照）
 */
public record SwapResponse(
        String swapKey,
        LocalDate opDate,
        String status,
        List<SwapPlanView> plans,
        List<SwapConflictView> conflicts,
        List<SwapSnapshotView> beforeSnapshots,
        List<SwapSnapshotView> afterSnapshots) {
}
