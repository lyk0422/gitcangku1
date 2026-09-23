package com.example.starter.blind.dto;

/**
 * 揭盲结果视图，包含处理代码与泄露登记凭据；仅申请人本人在申请已批准后可获取。
 *
 * @param requestId     揭盲申请编号
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param treatment     处理代码 A / B
 * @param status        申请状态（返回时必为 APPROVED）
 * @param exposureKey   泄露登记凭据，仅申请人本人可见，凭此登记自己发起的直接披露
 * @param reviewedAt    批准时间，Unix 毫秒，UTC
 */
public record UnblindResultView(
        String requestId,
        String experimentId,
        String participantId,
        String treatment,
        String status,
        String exposureKey,
        long reviewedAt
) {
}
