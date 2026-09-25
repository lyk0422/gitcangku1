package com.example.starter.container.dto;

import java.util.List;

/**
 * 容器巡检详情视图：容器当前状态 + 当前装载集合 + 全部巡检记录 + 全部逐件快照 + 全部复核记录。
 * 列表均按发生顺序返回。
 *
 * @param container   容器当前视图
 * @param items       当前装载证物键集合（按装载顺序）
 * @param inspections 历史巡检记录（含历史 FAIL，PASS 不改写 FAIL）
 * @param snapshots   历史逐件 FAIL 快照
 * @param reviews     历史复核封签记录
 */
public record ContainerDetailView(
        ContainerView container,
        List<String> items,
        List<ContainerInspectionView> inspections,
        List<ContainerItemSnapshotView> snapshots,
        List<ContainerReviewView> reviews) {
}
