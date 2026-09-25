package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 追缴记录视图。原到期时刻、逾期分钟数与说明在追缴时固化，不可变。
 *
 * @param reclaimKey     追缴业务键
 * @param loanKey        被追缴的借出业务键
 * @param evidenceKey    关联证物业务键
 * @param custodianId    提交追缴的保管人
 * @param borrowerId     被追缴的借出人
 * @param dueAt          原应还时刻（UTC），追缴时固化
 * @param overdueMinutes 追缴时刻相对原应还时刻的逾期分钟数
 * @param note           追缴说明
 * @param reclaimedAt    追缴时刻（UTC）
 */
public record ReclaimView(
        String reclaimKey,
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        LocalDateTime dueAt,
        long overdueMinutes,
        String note,
        LocalDateTime reclaimedAt) {
}
