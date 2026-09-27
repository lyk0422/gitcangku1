package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.util.List;

/**
 * 披露包内单件证物的冻结视图：截至时刻的链版本、位置、封签快照与完整链事件。
 *
 * @param evidenceKey            证物业务键
 * @param sortOrder              规范化集合序号，从 0 开始
 * @param chainVersion           截至时刻链版本
 * @param chainHash              截至时刻保管链指纹
 * @param custodianId            截至时刻位置（保管人）
 * @param statusAtCutoff         截至时刻重放证物状态
 * @param sealNo                 封条编号快照
 * @param sealOk                 截至时刻封签是否正常
 * @param latestInspectionPassed 截至时刻最近一次核验结果：null 从未核验
 * @param itemDigest             单件生成摘要
 * @param events                 截至时刻前（含）的有序链事件快照
 */
public record FrozenItemView(
        String evidenceKey,
        int sortOrder,
        int chainVersion,
        String chainHash,
        String custodianId,
        EvidenceStatus statusAtCutoff,
        String sealNo,
        boolean sealOk,
        Boolean latestInspectionPassed,
        String itemDigest,
        List<FrozenChainEventView> events) {
}
