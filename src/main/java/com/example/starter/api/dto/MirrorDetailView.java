package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 制品版本镜像登记明细视图：含当前可用性与登记时间，不可用镜像仍保留在明细中。
 *
 * @param available 可用性：true=可用（参与锁定固化与故障切换），false=不可用（记录保留）
 */
public record MirrorDetailView(
        String mirrorId,
        int priority,
        boolean available,
        Instant createdAt) {
}
