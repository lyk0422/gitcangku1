package com.example.starter.film.model;

import com.example.starter.film.support.Payloads;

import java.time.LocalDateTime;

/**
 * 审核基线明细：审核请求创建时每个计划片段固定的版次与摘要。
 *
 * @param id          行ID
 * @param reviewId    所属审核请求ID
 * @param seq         片段序号，从 1 开始
 * @param versionNo   固定的片段版次
 * @param digest      固定的内容摘要快照
 * @param fixedAt     固定时间，Asia/Shanghai
 */
public record ReviewSegmentBaseline(
        Long id,
        Long reviewId,
        int seq,
        int versionNo,
        String digest,
        LocalDateTime fixedAt
) implements Payloads.SnapshotLine {
}
