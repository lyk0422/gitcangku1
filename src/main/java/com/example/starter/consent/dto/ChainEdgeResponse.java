package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 有效委托链中的一条边视图（只读）。
 *
 * @param delegatorKey 委托方标识
 * @param processorKey 受托处理方标识
 * @param version      边版本
 * @param expiresAt    边到期时刻（UTC）
 */
public record ChainEdgeResponse(String delegatorKey, String processorKey, int version, Instant expiresAt) {
}
