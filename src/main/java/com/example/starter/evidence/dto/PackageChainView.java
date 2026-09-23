package com.example.starter.evidence.dto;

import com.example.starter.evidence.PackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合包链路证据视图：借出明细（含冻结封条版本）+ 全部归还批次（双人确认与逐件快照）
 * + 关闭快照。只读且稳定排序；关闭快照为不可变 JSON 文本，未关闭时为 null。
 *
 * @param packageKey    组合包业务键
 * @param status        组合包状态
 * @param loanAt        实际借出时刻（UTC）
 * @param dueAt         统一 UTC 应还时刻
 * @param items         全部借出明细（按包内稳定排序）
 * @param batches       全部归还批次（按提交顺序）
 * @param closeSnapshot 关闭快照 JSON（含全部借出与归还批次）；null 表示未关闭
 */
public record PackageChainView(
        String packageKey,
        PackageStatus status,
        LocalDateTime loanAt,
        LocalDateTime dueAt,
        List<PackageItemView> items,
        List<PackageBatchView> batches,
        String closeSnapshot) {
}
