package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定响应中的弃用告警视图：锁定时刻固化，之后窗口调整或替代撤回不回写。
 *
 * @param code               固定为 DEPRECATION_WARNING
 * @param replacementVersion 锁定时刻建议的同制品替代版本号
 * @param effectiveAt        锁定时刻记录的弃用生效时刻（UTC）
 * @param reason             锁定时刻记录的弃用说明，null 表示原窗口未填写
 */
public record DeprecationWarningView(
        String code,
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String reason) {

    /** 告警类型码。 */
    public static final String CODE = "DEPRECATION_WARNING";
}
