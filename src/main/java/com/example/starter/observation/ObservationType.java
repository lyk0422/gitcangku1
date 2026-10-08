package com.example.starter.observation;

import java.util.Locale;

/**
 * 观测记录类型：FIELD 为现场通用观测（地点/读数/备注三字段）；
 * SECTION 为断面观测点（断面号/横向毫米/高程毫米，三字段作为完整版本组整体合并）。
 */
public enum ObservationType {
    FIELD,
    SECTION;

    /**
     * 大小写不敏感地解析请求中的类型值；null 视为 FIELD（兼容未传类型的旧请求），非法值返回 null。
     */
    public static ObservationType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return FIELD;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "FIELD" -> FIELD;
            case "SECTION" -> SECTION;
            default -> null;
        };
    }
}
