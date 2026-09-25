package com.example.starter.container.dto;

import java.util.List;

/**
 * 容器巡检完整结果视图：巡检记录 + FAIL 时的逐件快照（PASS 为空列表）。
 * 同 inspectKey 同参重放时原样返回该完整结果。
 *
 * @param inspection 巡检记录视图
 * @param container  巡检后容器视图
 * @param snapshots  FAIL 巡检写入的逐件不可变快照（按件次顺序）；PASS 为空
 */
public record ContainerInspectResultView(
        ContainerInspectionView inspection,
        ContainerView container,
        List<ContainerItemSnapshotView> snapshots) {
}
