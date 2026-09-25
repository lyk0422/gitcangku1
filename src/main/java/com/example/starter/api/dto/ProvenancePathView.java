package com.example.starter.api.dto;

import java.util.List;

/**
 * 单个坐标的来源路径信息。
 *
 * @param dependencyPath 该坐标在锁定图中的一条完整依赖路径（根 -> 该坐标）
 * @param matched        是否命中有效证明
 * @param reason         未命中原因代码；命中时为 null
 * @param attestationId  命中的证明版本 ID；未命中为 null
 */
public record ProvenancePathView(
        String name,
        int version,
        List<String> dependencyPath,
        boolean matched,
        String reason,
        Long attestationId,
        String sourceRepository,
        String buildDigest,
        Integer attestationLevel) {
}
