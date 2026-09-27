package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交明细视图：批次头 + 全部证物快照 + 全部保管链条目（含反向链）。
 *
 * @param transferId     跨案移交批次标识
 * @param status         批次状态：COMPLETED 已移交 / REVOKED 已撤销（原链保留）
 * @param orderVersion   移交令版本（固化）
 * @param sourceCaseKey  来源案件键
 * @param targetCaseKey  目标案件键
 * @param items          证物快照列表（按证物键升序）
 * @param chain          保管链条目（含移出、移入与撤销反向链，按写入顺序）
 * @param createdAt      移交时间（UTC）
 * @param revokedAt      撤销时间（UTC）；null 表示未撤销
 */
public record CaseTransferView(
        String transferId,
        String status,
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        List<CaseTransferItemView> items,
        List<ChainEntryView> chain,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
