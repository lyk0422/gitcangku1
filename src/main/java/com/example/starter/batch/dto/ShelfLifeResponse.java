package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次有效期查询响应。validUntil 为当前有效期（生产时间 + 保质分钟 + 全部已确认延期顺延）；
 * expired/remainingMinutes 相对服务端当前时刻（到期后 remainingMinutes 为 0）；
 * totalExtendedMinutes 为累计顺延分钟，extensionCount 为已生效延期次数（最多 3）；
 * extensions 为按生效顺序排列的不可变延期历史。所有时间为 UTC instant。
 */
public record ShelfLifeResponse(
        String batchKey,
        Instant producedAt,
        long shelfLifeMinutes,
        Instant validUntil,
        boolean expired,
        long remainingMinutes,
        long totalExtendedMinutes,
        int extensionCount,
        List<ExtensionRecordResponse> extensions
) {
}
