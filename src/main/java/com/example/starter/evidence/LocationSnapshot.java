package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物历史位置轨迹实体，对应 evidence_location_snapshot 表。
 * 只追加：任何位置/保管人变更（入库、放入容器、交接接受、异常隔离、复核放行）均追加一行，
 * 历史行永不更新；隔离行保留原位置，复核放行不影响隔离行内容。
 *
 * @param id            主键
 * @param evidenceKey   关联证物业务键
 * @param containerKey  该时刻所在容器业务键；null 表示散置库房未入容器
 * @param custodianId   该时刻保管人
 * @param reason        轨迹原因
 * @param inspectionId  关联巡检单ID（隔离与放行时写入）；其他原因为 null
 * @param createdAt     轨迹记录时刻（Asia/Shanghai）
 */
public record LocationSnapshot(
        Long id,
        String evidenceKey,
        String containerKey,
        String custodianId,
        LocationReason reason,
        Long inspectionId,
        LocalDateTime createdAt) {
}
