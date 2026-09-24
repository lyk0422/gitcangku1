package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 按时刻一致视图响应：按 observationId 升序返回每条记录在该时刻的最后版本视图。
 *
 * @param asOfUtc 查询基准 UTC 时刻
 * @param items   逐条记录的按时刻视图
 */
public record AsOfResponse(
        Instant asOfUtc,
        List<Item> items) {

    /**
     * 单条记录在指定时刻的状态。
     *
     * @param observationId    观测记录唯一标识
     * @param state            时刻状态：ABSENT（时刻前尚未创建）/ ACTIVE / TOMBSTONE
     * @param version          该时刻最后一个版本号；ABSENT 时为 null
     * @param deleted          是否处于删除墓碑状态；ABSENT 为 false，TOMBSTONE 为 true
     * @param location         该时刻版本观测地点；ABSENT 与墓碑时省略
     * @param reading          该时刻版本观测读数原文；ABSENT 与墓碑时省略
     * @param note             该时刻版本观测备注；ABSENT 与墓碑时省略
     * @param lastResolutionId 该时刻之前（含该时刻）最近一次冲突解决记录标识；无则省略
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String observationId,
            String state,
            Integer version,
            boolean deleted,
            String location,
            String reading,
            String note,
            String lastResolutionId) {
    }
}
