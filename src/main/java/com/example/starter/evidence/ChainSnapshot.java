package com.example.starter.evidence;

import com.example.starter.evidence.dto.FrozenChainEventView;

import java.util.List;

/**
 * 单件证物按截至 UTC 时刻重放保管链得到的历史快照（中间结果，供披露包校验与固化）。
 *
 * @param evidenceKey            证物业务键
 * @param caseKey                所属案件键（当前值）
 * @param sealNo                 封条编号
 * @param chainVersion           截至时刻链版本（事件总数）
 * @param chainHash              截至时刻保管链指纹 SHA-256
 * @param itemDigest             单件生成摘要 SHA-256
 * @param custodianId            截至时刻位置（保管人）
 * @param status                 截至时刻重放证物状态
 * @param sealOk                 截至时刻封签是否正常
 * @param latestInspectionPassed 截至时刻最近一次核验结果：null 从未核验
 * @param chainContinuous        保管链是否连续
 * @param gaps                   链缺口描述
 * @param pendingTransfer        截至时刻是否存在待接收交接
 * @param borrowedUnreturned     截至时刻是否借出未归还
 * @param events                 截至时刻前（含）的有序链事件
 */
public record ChainSnapshot(
        String evidenceKey,
        String caseKey,
        String sealNo,
        int chainVersion,
        String chainHash,
        String itemDigest,
        String custodianId,
        EvidenceStatus status,
        boolean sealOk,
        Boolean latestInspectionPassed,
        boolean chainContinuous,
        List<String> gaps,
        boolean pendingTransfer,
        boolean borrowedUnreturned,
        List<FrozenChainEventView> events) {
}
