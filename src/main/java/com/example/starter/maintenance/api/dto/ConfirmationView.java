package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 隔离令确认视图。
 *
 * @param seqNo        确认顺序号（1 第一名角色，2 第二名角色）
 * @param operatorId   确认操作人
 * @param role         维护角色
 * @param requestId    确认请求 requestId
 * @param confirmedAt  确认时刻（UTC）
 */
public record ConfirmationView(
        int seqNo,
        String operatorId,
        String role,
        String requestId,
        Instant confirmedAt) {
}
