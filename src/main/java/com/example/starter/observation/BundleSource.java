package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 联合裁决字段来源：本地值（LOCAL）、远端值（REMOTE）、基线值（BASE）或显式新值（VALUE）。
 * AUTO 仅用于服务端落库的逐字段来源，表示非冲突字段按三方规则自动合并，不允许客户端提交。
 */
public enum BundleSource {
    LOCAL,
    REMOTE,
    BASE,
    VALUE,
    AUTO;

    /**
     * 大小写不敏感地解析请求中的来源；非法值返回 null，由业务层统一报 400。
     */
    @JsonCreator
    public static BundleSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LOCAL" -> LOCAL;
            case "REMOTE" -> REMOTE;
            case "BASE" -> BASE;
            case "VALUE" -> VALUE;
            case "AUTO" -> AUTO;
            default -> null;
        };
    }
}
