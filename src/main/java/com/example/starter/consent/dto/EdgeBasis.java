package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 不可变写入依据中的单边快照：记录写入时该委托边的完整版本信息。
 *
 * @param delegationKey 委托边全局唯一键
 * @param fromKey       边起点
 * @param toKey         边终点
 * @param version       写入时校验通过的边版本
 * @param expiresAt     边到期时刻（UTC）
 */
public record EdgeBasis(
        String delegationKey,
        String fromKey,
        String toKey,
        int version,
        Instant expiresAt) {
}
