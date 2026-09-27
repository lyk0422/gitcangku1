package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离审计事件（只追加）。
 *
 * @param eventId      事件标识，亦为稳定排序键
 * @param orderId      关联隔离令标识；设备级事件（停用）为 null
 * @param equipmentId  所属设备标识
 * @param eventType    事件类型：CREATED/CONFIRMED/EFFECTIVE/RELEASE_POINT/RELEASED/DEACTIVATED
 * @param lockPointId  关联锁定点标识；令级/设备级事件为 null
 * @param lockRole     确认事件角色 ISOLATOR/VERIFIER；其余事件 null
 * @param operatorId   事件操作人
 * @param requestId    产生事件的请求 requestId
 * @param detail       事件补充说明（如解除原因），无补充为 null
 * @param eventAt      事件发生时刻（UTC）
 */
public record IsolationEvent(
        long eventId,
        Long orderId,
        String equipmentId,
        String eventType,
        String lockPointId,
        String lockRole,
        String operatorId,
        String requestId,
        String detail,
        Instant eventAt) {
}
