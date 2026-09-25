package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationStatus;

import java.util.List;

/**
 * 库位库存视图：库位当前状态、库存版本及库内全部证物。
 *
 * @param locationCode 库位编码
 * @param status       库位状态
 * @param version      库存版本
 * @param evidence     当前位于该库位的证物列表
 */
public record LocationInventoryView(
        String locationCode,
        LocationStatus status,
        int version,
        List<EvidenceView> evidence) {
}
