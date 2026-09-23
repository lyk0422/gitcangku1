package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 字段级来源视图：不可变证据的只读表示。
 *
 * @param sourceRecordKey  字段取值来源的成员记录键
 * @param sourceGeneration 来源成员取值时的代次
 * @param sourceValue      锁定的字段原文
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldSourceView(
        String sourceRecordKey,
        int sourceGeneration,
        String sourceValue) {
}
