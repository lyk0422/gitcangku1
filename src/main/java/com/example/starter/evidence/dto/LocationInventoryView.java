package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationStatus;

import java.util.List;

/**
 * 库位库存视图：库位当前状态、库存版本及在库证物列表。
 *
 * @param locationCode 库位业务键
 * @param label        库位名称说明
 * @param status       库位状态
 * @param version      库存版本号（迁移申请 expectedVersion 应取该值）
 * @param evidence     当前位于该库位的证物列表（按入库顺序）
 */
public record LocationInventoryView(
        String locationCode,
        String label,
        LocationStatus status,
        int version,
        List<EvidenceView> evidence) {
}
