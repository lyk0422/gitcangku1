package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 更正附录视图（不可变）。
 *
 * @param id          附录标识
 * @param cycleNo     关联的已完成保养周期序号
 * @param operatorId  创建附录的操作人员
 * @param note        更正说明
 * @param createdAt   附录创建时刻（UTC）
 */
public record AppendixView(
        long id,
        int cycleNo,
        String operatorId,
        String note,
        Instant createdAt) {
}
