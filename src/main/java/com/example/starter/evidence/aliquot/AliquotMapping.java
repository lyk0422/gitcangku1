package com.example.starter.evidence.aliquot;

import java.time.LocalDateTime;

/**
 * 母样-数量-子样不可变映射实体，对应 aliquot_mapping 表。
 * 二次确认成功时一次性生成，每件母样耗用数量对应到同一子样，不可再修改。
 *
 * @param id         主键
 * @param aliquotKey 生成的子样业务键
 * @param requestId  来源联合取样单业务键
 * @param sampleKey  来源母样业务键
 * @param qty        该母样在本单耗用并映射到子样的数量，正整数
 * @param unit       耗用数量单位，与母样登记单位一致
 * @param createdAt  映射生成时间（Asia/Shanghai）
 */
public record AliquotMapping(
        Long id,
        String aliquotKey,
        String requestId,
        String sampleKey,
        long qty,
        String unit,
        LocalDateTime createdAt) {
}
