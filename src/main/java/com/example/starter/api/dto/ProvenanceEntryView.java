package com.example.starter.api.dto;

/**
 * 锁定图某坐标的来源路径视图。
 *
 * @param status OK 表示满足当前策略；否则为违规原因码（见 PolicyViolation.code）
 */
public record ProvenanceEntryView(
        String name,
        int version,
        String path,
        Integer attestationVersion,
        String repoId,
        String digest,
        Integer level,
        Boolean revoked,
        String status) {
}
