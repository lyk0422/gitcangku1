package com.example.starter.observation;

import java.time.Instant;

/**
 * 披露附页记录：对应 disclosure_appendix 表的一行；首次发布后的每次更正追加一行，不可变。
 *
 * @param observationId   观测记录唯一标识
 * @param seq             附页序号，每簇从 1 开始单调递增
 * @param version         该附页对应的观测版本号
 * @param location        该附页观测地点快照；删除墓碑为 null
 * @param reading         该附页观测读数快照（十进制原文）；删除墓碑为 null
 * @param note            该附页观测备注快照；删除墓碑为 null
 * @param deleted         该附页是否为删除墓碑
 * @param sourceRequestId 产生该附页的更正请求标识
 * @param recordedAtUtc   附页记录时刻（UTC）
 */
public record AppendixRecord(
        String observationId,
        int seq,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String sourceRequestId,
        Instant recordedAtUtc) {
}