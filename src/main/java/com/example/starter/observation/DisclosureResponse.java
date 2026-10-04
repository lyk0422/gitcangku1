package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 披露快照响应：首次发布的不可变内容；删除墓碑快照不返回业务字段。
 *
 * @param observationId 观测记录唯一标识
 * @param version       发布时刻胜出版本号
 * @param deleted       发布时刻记录是否为删除墓碑
 * @param location      发布时刻胜出观测地点（墓碑快照不返回）
 * @param reading       发布时刻胜出观测读数（墓碑快照不返回）
 * @param note          发布时刻胜出观测备注（墓碑快照不返回）
 * @param resolutions   发布时刻全部冲突裁决摘要，按解决时刻升序
 * @param qualityFlags  质量标记列表，按字典序排序
 * @param operator      执行首次发布的操作者标识
 * @param requestId     首次发布的请求标识
 * @param releasedAtUtc 首次发布时刻（UTC，ISO-8601）
 * @param appendixCount 当前附页数量实际值（首次发布后每次更正追加一页）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        List<ResolutionSummary> resolutions,
        List<String> qualityFlags,
        String operator,
        String requestId,
        Instant releasedAtUtc,
        int appendixCount) {
}