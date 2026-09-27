package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁文件条目内固化的单条见证快照。
 *
 * @param role       见证发布角色
 * @param checksum   见证提交的摘要固化值
 * @param attestedAt 见证提交时间固化值，UTC
 */
public record FrozenAttestationView(
        String role,
        String checksum,
        Instant attestedAt) {
}
