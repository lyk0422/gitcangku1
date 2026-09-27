package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 证据包检查项结果快照（不可变，历史版本行保留）。
 *
 * @param id              自增主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         所属保养周期序号
 * @param packageVersion  所属证据包版本
 * @param ordinal         模板内规范序号，读取时按其升序
 * @param itemCode        检查项编码
 * @param pass            是否 PASS（false 为 FAIL）
 * @param remark          备注（null 表示无备注）
 * @param submittedBy     结果提交人
 * @param createdAt       结果提交时刻（UTC）
 */
public record EvidenceResult(
        long id,
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int ordinal,
        String itemCode,
        boolean pass,
        String remark,
        String submittedBy,
        Instant createdAt) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
}
