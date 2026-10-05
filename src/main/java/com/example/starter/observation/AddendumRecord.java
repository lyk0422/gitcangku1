package com.example.starter.observation;

import java.time.Instant;

/**
 * 披露附页记录：对应 disclosure_addendum 表的一行；首次发布后的每个新版本追加一条，不可变。
 *
 * @param observationId 观测记录唯一标识
 * @param seq           附页序号，同一观测记录内从 1 开始单调递增
 * @param version       该附页对应的观测版本号
 * @param location      该版本观测地点快照；删除墓碑为 null
 * @param reading       该版本观测读数快照（十进制原文）；删除墓碑为 null
 * @param note          该版本观测备注快照；删除墓碑为 null
 * @param deleted       该版本是否为删除墓碑
 * @param requestId     产生该版本的请求标识
 * @param operator      操作者标识；合并/删除请求无操作者概念时为 null
 * @param createdAtUtc  附页生成时刻（UTC）
 */
public record AddendumRecord(
        String observationId,
        int seq,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String requestId,
        String operator,
        Instant createdAtUtc) {
}
