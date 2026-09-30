package com.example.starter.film.domain;

import java.time.Instant;

/**
 * 单个片段的一次修复提交；同一 {@code segmentNo} 的记录只追加、不改写，按 {@code versionNo} 组成版本链。
 *
 * @param id               版本记录主键
 * @param filmId           所属影片 ID
 * @param segmentNo        片段序号，从 1 开始
 * @param versionNo        该片段内的版次，首版为 1，逐次加 1
 * @param contentDigest    内容摘要（格式：sha256: 加 64 位十六进制）
 * @param basedOnVersion   本次提交基于的当前版次；首版为 null，其余必须等于提交前的最新版次
 * @param requestId        幂等请求号；同一影片内唯一，重复提交用于去重
 * @param createdAt        提交时间，UTC 时间线
 */
public record SegmentVersion(long id, long filmId, int segmentNo, int versionNo, String contentDigest,
                             Integer basedOnVersion, String requestId, Instant createdAt) {
}
