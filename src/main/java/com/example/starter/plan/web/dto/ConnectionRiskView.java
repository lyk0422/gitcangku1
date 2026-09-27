package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续风险记录视图（历史，不可改写）：固化风险判定所依据的前车版本、
 * 前车原新终到时刻、要求/实际接续分钟数与保障差额，以及可选改签时间窗。
 */
public record ConnectionRiskView(long id, long connectionId, String predecessorKey,
                                 int predecessorVersion, String successorKey,
                                 Instant predecessorOldArrivalUtc, Instant predecessorNewArrivalUtc,
                                 long requiredMinutes, long actualMinutes, long deficitMinutes,
                                 Instant windowStartUtc, Instant windowEndUtc) {
}
