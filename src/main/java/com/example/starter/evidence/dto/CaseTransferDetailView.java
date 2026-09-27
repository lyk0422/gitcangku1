package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 跨案移交明细视图：移交头 + 全部明细快照 + 全部双案链条目（均按写入顺序）。
 *
 * @param transfer 移交头
 * @param items    双案封存快照（同一 transferId，按批内序号排序）
 * @param chain    双案保管链条目（正反向链共用同一 transferId，按写入顺序）
 */
public record CaseTransferDetailView(
        CaseTransferView transfer,
        List<CaseTransferItemView> items,
        List<CaseTransferChainView> chain) {
}
