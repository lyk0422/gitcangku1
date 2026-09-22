package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 冲突字段人工选择：保留当前值（CURRENT）或接受候选值（CANDIDATE）。不允许选择 BASE。
 */
public enum FieldSelection {
    CURRENT,
    CANDIDATE;

    /**
     * 大小写不敏感地解析请求中的选择值；非法值返回 null，由业务层统一报 400。
     */
    @JsonCreator
    public static FieldSelection fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CURRENT" -> CURRENT;
            case "CANDIDATE" -> CANDIDATE;
            default -> null;
        };
    }
}
