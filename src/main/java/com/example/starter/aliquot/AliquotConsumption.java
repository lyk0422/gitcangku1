package com.example.starter.aliquot;

import java.time.LocalDateTime;

/**
 * 母样耗用与子样映射实体，对应 aliquot_consumption 表。
 * 耗用成功时一次写入，不可变；每件母样生成一个 SEALED 子样。
 *
 * @param id                主键
 * @param requestId         所属联合取样单 id
 * @param sampleKey         被耗用的母样业务键
 * @param quantity          本次耗用数量
 * @param childEvidenceKey  生成的 SEALED 子样证物键
 * @param createdAt         耗用（子样生成）时间
 */
public record AliquotConsumption(
        Long id,
        long requestId,
        String sampleKey,
        long quantity,
        String childEvidenceKey,
        LocalDateTime createdAt) {
}
