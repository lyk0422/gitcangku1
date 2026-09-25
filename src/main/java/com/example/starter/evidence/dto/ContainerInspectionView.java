package com.example.starter.evidence.dto;

import com.example.starter.evidence.SealResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器巡检视图：巡检记录及其逐件不可变快照（仅 FAIL 巡检有快照）。
 *
 * @param id               巡检记录 id
 * @param containerKey     容器业务键
 * @param inspectorId      检查人
 * @param result           封签结果 PASS / FAIL
 * @param note             巡检说明
 * @param inspectedAt      实际巡检时刻（UTC）
 * @param containerVersion 巡检时容器版本号
 * @param createdAt        记录创建时间（Asia/Shanghai）
 * @param snapshots        逐件快照（PASS 为空列表）
 */
public record ContainerInspectionView(
        Long id,
        String containerKey,
        String inspectorId,
        SealResult result,
        String note,
        LocalDateTime inspectedAt,
        long containerVersion,
        LocalDateTime createdAt,
        List<SnapshotView> snapshots) {
}
