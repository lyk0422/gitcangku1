package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 跨案移交明细视图：主表 + 双案封存快照 + 双案保管链（均按写入顺序）。
 *
 * @param transfer 移交主表视图
 * @param items    双案封存快照（每证物一行，固化双方案件、原位置、封签版本与令版本）
 * @param chain    双案保管链条目（移交与撤销反向链）
 */
public record CaseTransferDetailView(
        CaseTransferView transfer,
        List<CaseTransferItemView> items,
        List<CaseTransferChainEntryView> chain) {
}
