package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案封存快照（批次明细），对应 case_transfer_item 表。
 * 移交时固化双方案件、原位置、封签版本与令版本，之后不可变。
 *
 * @param id            主键
 * @param transferId    所属跨案移交批次业务键
 * @param evidenceKey   证物业务键
 * @param sourceCaseKey 封存时来源案件键
 * @param targetCaseKey 封存时目标案件键
 * @param fromLocation  原位置（移交前存放位置）；null 表示移交前未登记
 * @param sealVersion   封存时封签版本快照
 * @param orderVersion  封存时移交令版本
 * @param transferSeq   移交时该证物交接链最大序号；撤销时增长即表示目标案件已发生后续交接
 * @param createdAt     快照生成时间（UTC）
 */
public record CaseTransferItem(
        Long id,
        String transferId,
        String evidenceKey,
        String sourceCaseKey,
        String targetCaseKey,
        String fromLocation,
        int sealVersion,
        String orderVersion,
        long transferSeq,
        LocalDateTime createdAt) {
}
