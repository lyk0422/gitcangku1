package com.example.starter.repo;

/**
 * 跑道关闭窗口持久化记录（UTC 左闭右开，不可变）。
 *
 * @param closureId      关闭窗口唯一标识
 * @param runwayId       所属跑道标识
 * @param startUtc       关闭开始（含），epoch 毫秒（UTC）
 * @param endUtc         关闭结束（不含），epoch 毫秒（UTC）
 * @param allowEmergency 是否允许紧急例外
 * @param operator       登记操作者
 * @param runwayVersion  本次变更生效后的跑道版本
 * @param closureKey     关闭变更幂等键
 * @param createdAt      创建时间（epoch 毫秒）
 */
public record ClosurePo(String closureId, String runwayId, long startUtc, long endUtc,
                        boolean allowEmergency, String operator, int runwayVersion,
                        String closureKey, long createdAt) {
}
