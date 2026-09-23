package com.example.starter.domain;

/**
 * 替代候选被拒绝的记录：坐标与拒绝原因，用于锁图解释快照。
 */
public record RejectedCandidate(String name, int version, String reason) {
}
