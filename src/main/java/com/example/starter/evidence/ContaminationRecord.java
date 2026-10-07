package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 污染登记实体，对应 contamination_record 表。只追加，污染不可解除。
 *
 * @param id               主键
 * @param contaminationKey 污染登记业务键（取登记请求 requestId）
 * @param evidenceKey      污染起点证物业务键
 * @param startSeq         污染起点事件序号，从该序号（含）起向后传播
 * @param registeredBy     登记操作人（起点证物当前保管人）
 * @param affectedCount    本次污染集合证物总数（含起点）
 * @param createdAt        登记时刻（UTC）
 */
public record ContaminationRecord(
        Long id,
        String contaminationKey,
        String evidenceKey,
        long startSeq,
        String registeredBy,
        int affectedCount,
        LocalDateTime createdAt) {
}
