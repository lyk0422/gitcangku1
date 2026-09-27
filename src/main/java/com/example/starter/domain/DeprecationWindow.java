package com.example.starter.domain;

import java.time.Instant;

/**
 * 有效的制品弃用窗口（已撤销窗口不进入快照）。
 *
 * @param id                 窗口记录 ID
 * @param name               制品名称
 * @param version            被弃用版本号
 * @param effectiveAt        弃用生效时刻（UTC），[登记, 生效) 左闭右开
 * @param replacementVersion 同制品替代版本号
 * @param reason             弃用说明，null 表示未填写
 */
public record DeprecationWindow(
        long id,
        String name,
        int version,
        Instant effectiveAt,
        int replacementVersion,
        String reason) {
}
