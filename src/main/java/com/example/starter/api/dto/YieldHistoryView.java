package com.example.starter.api.dto;

/**
 * 让渡不可变历史只读视图，按 seq 升序返回，固化原审查版本与让渡原因。
 *
 * @param seq             单调递增让渡序号
 * @param corridorId      走廊标识
 * @param corridorVersion 让渡时走廊版本
 * @param occupancyId     被让渡原占用标识
 * @param routeId         被让渡航线标识
 * @param reviewId        原占用引用的审查标识
 * @param routeVersion    原审查航线版本（固化）
 * @param airspaceVersion 原审查空域版本（固化）
 * @param reason          让渡原因（固化）
 * @param requestId       走廊建立请求标识
 * @param yieldedMs       让渡时刻，epoch 毫秒 UTC
 */
public record YieldHistoryView(long seq, String corridorId, int corridorVersion,
                               String occupancyId, String routeId, String reviewId,
                               int routeVersion, long airspaceVersion, String reason,
                               String requestId, long yieldedMs) {
}
