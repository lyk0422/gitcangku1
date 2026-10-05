package com.example.starter.observation;

import java.time.Instant;

/**
 * 发布后更正附页记录：对应 embargo_release_addendum 表的一行。
 * 首次发布之后的每个新版本（合并/解决/删除）追加一行，不改写首次快照。
 *
 * @param addendumId    附页标识（&lt;observationId&gt;@&lt;version&gt;）
 * @param observationId 观测记录唯一标识（簇）
 * @param releaseId     所属首次发布快照标识
 * @param version       该附页对应的观测版本号
 * @param location      该版本观测地点；墓碑版本为 null
 * @param reading       该版本观测读数（十进制原文）；墓碑版本为 null
 * @param note          该版本观测备注；墓碑版本为 null
 * @param deleted       该版本是否为删除墓碑
 * @param operation     产生该附页的操作类型：MERGE / RESOLVE / DELETE
 * @param requestId     产生该附页的请求标识
 * @param createdAtUtc  附页生成时刻（UTC）
 */
public record ReleaseAddendumRecord(
        String addendumId,
        String observationId,
        String releaseId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String operation,
        String requestId,
        Instant createdAtUtc) {
}
