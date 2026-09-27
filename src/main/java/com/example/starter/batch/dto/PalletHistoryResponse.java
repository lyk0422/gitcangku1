package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 托盘历史（只读）：全部装载事件按发生顺序返回，解除/装运不删除记录。
 * from/to 为可选 UTC 时间过滤，按装载时间左闭右开 [from, to) 生效；未提供时为 null。
 */
public record PalletHistoryResponse(
        PalletResponse pallet,
        List<PalletLoadEntry> events,
        ShipmentSnapshot shipment,
        DisposalInfo disposal,
        Instant from,
        Instant to
) {
}
