package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * peer 依赖声明结果视图。
 *
 * @param operator          登记操作者（原样回显）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 * @param peers             已登记的 peer 声明，按名称升序
 */
public record PeerDeclarationResponse(
        String name,
        int version,
        String operator,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> peers) {
}
