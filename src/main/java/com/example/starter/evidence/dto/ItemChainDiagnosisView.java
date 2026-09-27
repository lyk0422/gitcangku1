package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.util.List;

/**
 * 逐件保管链诊断视图：基于冻结快照重放校验链连续性与截至时刻状态。
 *
 * @param evidenceKey              证物业务键
 * @param sortOrder                规范化集合序号
 * @param chainVersion             截至时刻链版本
 * @param chainHash                截至时刻保管链指纹
 * @param chainContinuous          保管链是否连续：存在入库起点且每次已接受交接的保管人衔接一致
 * @param gaps                     链缺口描述列表；空列表表示连续
 * @param custodianIdAtCutoff      截至时刻位置（保管人）
 * @param statusAtCutoff           截至时刻重放状态
 * @param sealOkAtCutoff           截至时刻封签是否正常
 * @param pendingTransferAtCutoff  截至时刻是否存在待接收交接
 * @param borrowedUnreturnedAtCutoff 截至时刻是否借出未归还
 */
public record ItemChainDiagnosisView(
        String evidenceKey,
        int sortOrder,
        int chainVersion,
        String chainHash,
        boolean chainContinuous,
        List<String> gaps,
        String custodianIdAtCutoff,
        EvidenceStatus statusAtCutoff,
        boolean sealOkAtCutoff,
        boolean pendingTransferAtCutoff,
        boolean borrowedUnreturnedAtCutoff) {
}
