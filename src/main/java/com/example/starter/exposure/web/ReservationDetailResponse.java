package com.example.starter.exposure.web;

import java.util.List;

/**
 * 预占单只读明细视图：预占不可变快照 + 归因窗口版本信息 + 回执历史（按受理时刻稳定升序）。
 *
 * @param reservation   预占单最新状态视图（历史字段与 null 语义稳定）
 * @param windowMinutes 裁决/快照窗口要求分钟数（= reservation.attributionWindowMinutes）
 * @param windowStartUtc 窗口起点（=预占时刻，含），epoch 毫秒，UTC
 * @param windowEndUtc  窗口终点（起点 + 窗口分钟，不含），epoch 毫秒，UTC
 * @param receipts      该预占全部不可变回执流水，按受理时刻、流水编号升序
 */
public record ReservationDetailResponse(
        ReservationResponse reservation,
        int windowMinutes,
        long windowStartUtc,
        long windowEndUtc,
        List<ReceiptItemResult> receipts
) {
}
