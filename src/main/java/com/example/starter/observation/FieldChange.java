package com.example.starter.observation;

/**
 * 字段差异领域对象：附录中单个字段的旧值与新值。
 *
 * @param field    字段名：location / reading / note
 * @param oldValue 字段旧值（读数字段为十进制原文）
 * @param newValue 字段新值（读数字段为十进制原文）
 */
public record FieldChange(
        String field,
        String oldValue,
        String newValue) {
}
