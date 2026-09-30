package com.example.starter.film.model;

import com.example.starter.film.support.Payloads;

/**
 * 母版片段快照：按序号固定该瞬间的版次与摘要。行一经写入永不更新。
 *
 * @param id          行ID
 * @param masterId    所属母版ID
 * @param seq         片段序号，从 1 开始
 * @param versionNo   固定的片段版次
 * @param digest      固定的内容摘要快照
 */
public record MasterSegment(
        Long id,
        Long masterId,
        int seq,
        int versionNo,
        String digest
) implements Payloads.SnapshotLine {
}
