package com.example.starter.batch.dto;

import java.util.List;

/**
 * 托盘明细（只读）：托盘概要 + 当前在托装载 + 装运快照（未装运为 null）+ 处置信息（未处置为 null）。
 */
public record PalletDetailResponse(
        PalletResponse pallet,
        List<PalletLoadEntry> currentLoads,
        ShipmentSnapshot shipment,
        DisposalInfo disposal
) {
}
