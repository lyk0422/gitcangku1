package com.example.starter.observation;

/**
 * 字段级不可变证据行：对应 cluster_field_source 表，canonical 每个业务字段恰好一行。
 *
 * @param clusterKey      所属归并簇标识
 * @param fieldName       业务字段名：location / reading / note
 * @param sourceRecordId  字段取值来源的成员记录键
 * @param sourceGeneration 来源成员取值时的代次
 * @param sourceValue     归并确认时锁定的字段原文
 */
public record ClusterFieldSourceRecord(
        String clusterKey,
        String fieldName,
        String sourceRecordId,
        int sourceGeneration,
        String sourceValue) {
}
