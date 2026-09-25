package com.example.starter.api.dto;

/**
 * 跑道关闭窗口视图（UTC 左闭右开）。
 *
 * @param closureId      关闭窗口唯一标识
 * @param startUtc       关闭开始（含），UTC epoch 毫秒
 * @param endUtc         关闭结束（不含），UTC epoch 毫秒
 * @param allowEmergency 是否允许紧急例外
 * @param operator       登记操作者
 * @param runwayVersion  该窗口生效后的跑道版本
 * @param closureKey     登记时使用的幂等键
 * @param createdAt      创建时间（epoch 毫秒）
 */
public record ClosureWindowDto(String closureId, long startUtc, long endUtc,
                               boolean allowEmergency, String operator, int runwayVersion,
                               String closureKey, long createdAt) {
}
