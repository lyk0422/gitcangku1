package com.example.starter.observation;

/**
 * 簇字段级溯源证据：对应 cluster_field_source 表的一行，每个业务字段恰好一条，不可变。
 *
 * @param clusterKey       所属簇标识
 * @param fieldName        业务字段名：location / reading / note
 * @param sourceRecordKey  字段取值来源的成员记录键
 * @param sourceGeneration 来源成员被冻结的 generation
 * @param sourceValue      实际采纳的字段值（reading 为十进制原文）
 */
public record ClusterFieldSource(
        String clusterKey,
        String fieldName,
        String sourceRecordKey,
        int sourceGeneration,
        String sourceValue) {
}
