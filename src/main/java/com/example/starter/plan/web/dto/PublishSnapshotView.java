package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照视图：固化发布时刻的危险品编组、限制区段开放清单、配置版本与校验结论。
 * 字段排序、时间精度（UTC 毫秒转 Instant 输出）与 null 语义保持稳定：
 * 无生效配置时 configVersionNo/configFingerprint/configVersionId 为 null。
 */
public record PublishSnapshotView(Long snapshotId, Long configVersionId, Integer configVersionNo,
                                  String configFingerprint, String conclusion, String operator,
                                  Instant createdAtUtc, List<HazardCarView> hazardCars,
                                  List<SnapshotSectionView> restrictedSections) {

    /**
     * 快照固化的限制区段开放条目。
     */
    public record SnapshotSectionView(String sectionId, String hazardClass) {
    }
}
