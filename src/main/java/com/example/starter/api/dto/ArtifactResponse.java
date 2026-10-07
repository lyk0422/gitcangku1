package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param operator          执行本次写入的非空操作者
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 * @param peers             peer 依赖声明（按名称升序），不建立可达边
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        String operator,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies,
        List<PeerView> peers) {
}
