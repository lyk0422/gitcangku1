package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本。
 *
 * @param name      被锁定制品名称
 * @param version   被锁定的精确版本号，正整数
 * @param riskLevel 锁定时该版本的风险级别（NONE/LOW/MEDIUM/HIGH/CRITICAL），历史固化
 * @param exception 高风险依赖锁定时固化的豁免依据；无风险或未使用豁免时为 null
 */
public record LockEntryResponse(
        String name,
        int version,
        String riskLevel,
        ExceptionBasis exception) {

    /**
     * 历史锁文件中固化的豁免依据快照。
     *
     * @param exceptionId      豁免 ID
     * @param exceptionVersion 锁定时使用的申请版本号（两名审批人确认的同一版本）
     * @param statusAtLock     锁定瞬间的豁免状态（始终为 APPROVED）
     * @param approvers        审批该申请版本的两名不同审批人，按审批时间升序
     */
    public record ExceptionBasis(
            long exceptionId,
            int exceptionVersion,
            String statusAtLock,
            List<String> approvers) {
    }
}
