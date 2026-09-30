package com.example.starter.evidence.dto;

import com.example.starter.evidence.LocationReason;

import java.time.LocalDateTime;

/**
 * 证物历史位置轨迹视图。轨迹只追加，复核放行不改写历史行。
 *
 * @param containerKey 该时刻所在容器业务键；null 表示散置库房未入容器
 * @param custodianId  该时刻保管人
 * @param reason       轨迹原因
 * @param inspectionId 关联巡检单ID（隔离与放行时存在）；其他原因为 null
 * @param createdAt    轨迹记录时刻（Asia/Shanghai）
 */
public record LocationView(
        String containerKey,
        String custodianId,
        LocationReason reason,
        Long inspectionId,
        LocalDateTime createdAt) {
}
