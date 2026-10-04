package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 披露附页响应：首次发布后单次更正的内容快照；墓碑附页不返回业务字段。
 *
 * @param observationId   观测记录唯一标识
 * @param seq             附页序号，每簇从 1 开始单调递增
 * @param version         该附页对应的观测版本号
 * @param deleted         该附页是否为删除墓碑
 * @param location        该附页观测地点（墓碑附页不返回）
 * @param reading         该附页观测读数（墓碑附页不返回）
 * @param note            该附页观测备注（墓碑附页不返回）
 * @param sourceRequestId 产生该附页的更正请求标识
 * @param recordedAtUtc   附页记录时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppendixResponse(
        String observationId,
        int seq,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String sourceRequestId,
        Instant recordedAtUtc) {
}