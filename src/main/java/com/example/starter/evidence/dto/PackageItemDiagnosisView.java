package com.example.starter.evidence.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 披露包逐件链诊断视图：冻结快照与当前实时链状态对照，
 * 用于核验冻结后发生的迁移、复核或新增链记录是否偏离快照（包本身不被改写）。
 *
 * @param packageKey          披露包业务键
 * @param evidenceKey         证物业务键
 * @param seqNo               包内规范排序位次
 * @param frozenCustodianId   截至时刻保管人（位置快照）
 * @param frozenSealIntact    截至时刻封签是否正常
 * @param frozenChainVersion  截至时刻链版本
 * @param frozenChainDigest   截至时刻链摘要
 * @param frozenSnapshot      截至时刻冻结快照 JSON（含链事件清单）
 * @param currentCustodianId  当前保管人
 * @param currentStatus       当前证物状态
 * @param currentChainVersion 当前链版本（全部已生效链事件总数）
 * @param currentChainDigest  当前链摘要
 * @param changedSinceFreeze  冻结后链是否发生变化（链版本或摘要不同）
 */
public record PackageItemDiagnosisView(
        String packageKey,
        String evidenceKey,
        int seqNo,
        String frozenCustodianId,
        boolean frozenSealIntact,
        int frozenChainVersion,
        String frozenChainDigest,
        JsonNode frozenSnapshot,
        String currentCustodianId,
        String currentStatus,
        int currentChainVersion,
        String currentChainDigest,
        boolean changedSinceFreeze) {
}
