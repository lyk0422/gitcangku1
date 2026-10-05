package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 披露附页响应：首次发布后单次更正产生的版本快照。
 *
 * @param observationId 观测记录唯一标识
 * @param seq           附页序号，从 1 开始单调递增
 * @param version       该附页对应的观测版本号
 * @param deleted       该版本是否为删除墓碑
 * @param location      该版本观测地点；墓碑为 null（响应中省略）
 * @param reading       该版本观测读数；墓碑为 null（响应中省略）
 * @param note          该版本观测备注；墓碑为 null（响应中省略）
 * @param requestId     产生该版本的请求标识
 * @param operator      操作者标识；合并/删除请求无操作者概念时为 null（响应中省略）
 * @param createdAtUtc  附页生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddendumResponse(
        String observationId,
        int seq,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String requestId,
        String operator,
        Instant createdAtUtc) {

    /**
     * 由附页记录构造响应。
     */
    public static AddendumResponse of(AddendumRecord record) {
        return new AddendumResponse(
                record.observationId(),
                record.seq(),
                record.version(),
                record.deleted(),
                record.location(),
                record.reading(),
                record.note(),
                record.requestId(),
                record.operator(),
                record.createdAtUtc());
    }
}
