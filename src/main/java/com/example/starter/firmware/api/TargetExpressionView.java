package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TagExpression;

import java.util.List;

/**
 * 规范化排序后的标签表达式视图。三组均非 null（无条件时为空列表）。
 */
public record TargetExpressionView(List<String> include, List<String> exclude, List<String> anyOf) {

    public static TargetExpressionView of(TagExpression expression) {
        return new TargetExpressionView(expression.include(), expression.exclude(), expression.anyOf());
    }
}
