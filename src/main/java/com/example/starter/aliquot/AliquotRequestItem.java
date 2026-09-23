package com.example.starter.aliquot;

import java.time.LocalDateTime;

/**
 * 联合取样单母样明细实体，对应 aliquot_request_item 表。
 * 申请时写入；sampleVersion 为申请时母样版本快照。
 *
 * @param id            主键
 * @param requestId     所属联合取样单 id
 * @param sampleKey     母样业务键
 * @param quantity      从该母样取用量，正整数
 * @param sampleVersion 申请时母样版本快照
 * @param createdAt     明细创建时间
 */
public record AliquotRequestItem(
        Long id,
        long requestId,
        String sampleKey,
        long quantity,
        long sampleVersion,
        LocalDateTime createdAt) {
}
