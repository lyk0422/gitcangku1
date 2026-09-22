package com.example.starter.web.dto;

/**
 * 揭盲结果视图，仅揭盲申请的申请人可查询。
 *
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param blindCode     盲码
 * @param treatmentCode 揭盲后的处理代码（A / B）
 * @param blockNo       区组号
 * @param status        申请状态
 */
public record UnblindResultView(
        String experimentId,
        String participantId,
        String blindCode,
        String treatmentCode,
        int blockNo,
        String status
) {
}
