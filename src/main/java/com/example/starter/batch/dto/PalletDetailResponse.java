package com.example.starter.batch.dto;

import java.util.List;

/**
 * 托盘只读诊断视图：当前概要、当前 ACTIVE 装载、完整装载历史（含已解除行）、
 * 装运快照（未装运时为 null）、事件历史与召回联动待复核标记。
 */
public record PalletDetailResponse(
        PalletResponse pallet,
        List<LoadingDetail> history,
        ShipPalletResponse shipment,
        List<PalletEventResponse> events,
        List<ReviewFlagResponse> reviewFlags
) {
}
