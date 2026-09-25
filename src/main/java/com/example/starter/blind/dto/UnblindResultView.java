package com.example.starter.blind.dto;

/**
 * 揭盲结果视图，包含处理代码；仅申请人本人在申请已批准后可获取。
 *
 * @param requestId     揭盲申请编号
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param treatment     处理代码 A / B
 * @param status        申请状态（返回时必为 APPROVED）
 * @param reviewedAt    批准时间，Unix 毫秒，UTC
 * @param unblindType   REGULAR / EMERGENCY
 */
public record UnblindResultView(
        String requestId,
        String experimentId,
        String participantId,
        String treatment,
        String status,
        long reviewedAt,
        String unblindType
) {
}
