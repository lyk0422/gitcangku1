package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 联合裁决逐字段取值来源：本地当前值 LOCAL、离线候选 REMOTE、基线版本 BASE、人工显式新值 EXPLICIT。
 */
public enum ArbitrationSource {
    LOCAL,
    REMOTE,
    BASE,
    EXPLICIT;

    /**
     * 大小写不敏感地解析请求中的来源值；非法值返回 null，由业务层统一报 400。
     */
    @JsonCreator
    public static ArbitrationSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LOCAL" -> LOCAL;
            case "REMOTE" -> REMOTE;
            case "BASE" -> BASE;
            case "EXPLICIT" -> EXPLICIT;
            default -> null;
        };
    }
}
