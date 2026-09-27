package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 见证操作（提交见证/撤销见证）的响应视图。
 *
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record AttestationResponse(
        String name,
        int version,
        String checksum,
        List<AttestationView> attestations,
        long repositoryVersion,
        Instant actedAt) {
}
