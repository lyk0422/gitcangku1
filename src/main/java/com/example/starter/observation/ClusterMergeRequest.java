package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 重复观测簇归并提交请求：审核人基于预览冻结内容提交完整成员集合与逐字段来源。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param clusterKey       归并簇标识，全局唯一；复用已存在 clusterKey 仅允许同参重放
 * @param canonicalRecordId 归并产生的新主记录键，不得与既有记录键冲突，也不得等于任一成员键
 * @param members          全部“记录键 + 冻结代次（generation）”，数量必须为 2-20
 * @param fieldSources     每个业务字段（location/reading/note）的来源记录键，必须恰好覆盖一次
 * @param operator         执行归并的审核人标识
 */
public record ClusterMergeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String clusterKey,
        @NotBlank @Size(max = 64) String canonicalRecordId,
        @NotNull @Size(min = 2, max = 20) @Valid List<MemberGeneration> members,
        @NotNull @NotEmpty @Valid Map<@NotBlank String, @NotNull @Valid FieldSource> fieldSources,
        @NotBlank @Size(max = 128) String operator) {

    /**
     * 成员记录键与其预览时冻结的代次；确认时任一记录代次已变化均 409。
     */
    public record MemberGeneration(
            @NotBlank @Size(max = 64) String recordKey,
            @NotNull @jakarta.validation.constraints.Min(1) Integer generation) {
    }

    /**
     * 单个业务字段的来源记录键；来源必须在提交的成员集合内。
     */
    public record FieldSource(
            @NotBlank @Size(max = 64) String sourceRecordKey) {
    }
}
