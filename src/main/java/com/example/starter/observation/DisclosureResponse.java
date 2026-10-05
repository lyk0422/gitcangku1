package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 首次到期发布披露快照响应：发布时胜出版本内容、全部冲突裁决与质量标记，生成后不可变。
 *
 * @param observationId       观测记录唯一标识
 * @param winningVersion      发布时胜出版本号
 * @param deleted             胜出版本是否为删除墓碑
 * @param location            胜出版本观测地点；墓碑为 null（响应中省略）
 * @param reading             胜出版本观测读数；墓碑为 null（响应中省略）
 * @param note                胜出版本观测备注；墓碑为 null（响应中省略）
 * @param conflictResolutions 发布时全部冲突裁决记录，按解决时刻升序
 * @param qualityFlags        发布时质量标记
 * @param embargoUntilUtc     对应禁运截止时刻（UTC，ISO-8601）
 * @param releasedAtUtc       首次发布时刻（UTC，ISO-8601）
 * @param operator            执行发布的操作者标识
 * @param requestId           发布请求标识
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureResponse(
        String observationId,
        int winningVersion,
        boolean deleted,
        String location,
        String reading,
        String note,
        List<DisclosureResolutionItem> conflictResolutions,
        List<String> qualityFlags,
        Instant embargoUntilUtc,
        Instant releasedAtUtc,
        String operator,
        String requestId) {

    /**
     * 由不可变快照记录构造响应。
     */
    public static DisclosureResponse of(DisclosureSnapshotRecord record) {
        return new DisclosureResponse(
                record.observationId(),
                record.winningVersion(),
                record.deleted(),
                record.location(),
                record.reading(),
                record.note(),
                List.copyOf(record.conflictResolutions()),
                List.copyOf(record.qualityFlags()),
                record.embargoUntilUtc(),
                record.releasedAtUtc(),
                record.operator(),
                record.requestId());
    }
}
