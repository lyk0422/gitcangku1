package com.example.starter.evidence.aliquot.dto;

/**
 * 母样-数量-子样不可变映射只读视图。
 *
 * @param sampleKey  来源母样业务键
 * @param qty        该母样耗用并映射到子样的数量
 * @param unit       耗用数量单位
 * @param aliquotKey 生成的子样业务键
 */
public record AliquotMappingView(
        String sampleKey,
        long qty,
        String unit,
        String aliquotKey) {
}
