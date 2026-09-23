package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 当前有效委托链中的一条边视图。
 *
 * @param delegationKey 委托边全局唯一键
 * @param fromKey       边起点
 * @param toKey         边终点
 * @param version       边版本
 * @param expiresAt     边到期时刻（UTC）
 */
public record ChainEdgeResponse(
        String delegationKey,
        String fromKey,
        String toKey,
        int version,
        Instant expiresAt) {
}
