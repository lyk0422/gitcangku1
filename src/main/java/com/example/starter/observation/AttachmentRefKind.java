package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 合并快照/人工冲突选择中对附件摘要的引用类型：引用原始摘要，或引用指定脱敏版本。
 */
public enum AttachmentRefKind {
    ORIGINAL,
    REDACTED;

    /**
     * 大小写不敏感地解析请求中的引用类型；非法值返回 null，由业务层统一报 400。
     */
    @JsonCreator
    public static AttachmentRefKind fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ORIGINAL" -> ORIGINAL;
            case "REDACTED" -> REDACTED;
            default -> null;
        };
    }
}
