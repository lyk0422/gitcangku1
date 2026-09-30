package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 容器详情视图：容器当前状态与当前箱内清单 + 全部巡检记录（含各自隔离清单）。
 *
 * @param container   容器当前视图
 * @param inspections 历史巡检记录（按发生顺序），复核放行后保留
 */
public record ContainerDetailView(
        ContainerView container,
        List<ContainerInspectionView> inspections) {
}
