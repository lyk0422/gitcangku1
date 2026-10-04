package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 会话消息携带的观测操作类型：创建（CREATE）或离线提交三方合并（MERGE）。
 */
public enum SessionOperation {
    CREATE,
    MERGE;

    /**
     * 大小写不敏感地解析请求中的操作值；非法值返回 null，由业务层统一报 400。
     */
    @JsonCreator
    public static SessionOperation fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CREATE" -> CREATE;
            case "MERGE" -> MERGE;
            default -> null;
        };
    }
}