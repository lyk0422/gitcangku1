package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 污染登记视图，含冻结的传播路径。
 *
 * @param contaminationKey 污染登记业务键
 * @param evidenceKey      污染起点证物业务键
 * @param startSeq         污染起点事件序号
 * @param registeredBy     登记操作人
 * @param affected         受影响证物及传播来源批次（起点在前）
 * @param createdAt        登记时刻（UTC）
 */
public record ContaminationView(
        String contaminationKey,
        String evidenceKey,
        long startSeq,
        String registeredBy,
        List<AffectedEvidenceView> affected,
        LocalDateTime createdAt) {
}
