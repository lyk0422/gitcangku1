package com.example.starter.evidence.aliquot;

import java.time.LocalDateTime;

/**
 * 联合取样单明细实体，对应 sampling_item 表。每个母样一行。
 *
 * @param id                  主键
 * @param requestId           所属联合取样单业务键
 * @param sampleKey           母样业务键
 * @param qty                 从该母样取用量，正整数
 * @param unit                取用时母样单位
 * @param sampleVersion       申请预留成功时的母样版本，二次确认须逐件携带并比对
 * @param evidenceVersion     申请预留成功时的证物版本快照，期间任何证物变更都会使其变化
 * @param custodianSnapshot   申请时母样当前保管人快照
 * @param sealStatusSnapshot  申请时封条状态快照
 * @param createdAt           明细创建时间（Asia/Shanghai）
 */
public record SamplingItem(
        Long id,
        String requestId,
        String sampleKey,
        long qty,
        String unit,
        long sampleVersion,
        long evidenceVersion,
        String custodianSnapshot,
        String sealStatusSnapshot,
        LocalDateTime createdAt) {
}
