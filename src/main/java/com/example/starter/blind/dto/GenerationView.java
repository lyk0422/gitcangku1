package com.example.starter.blind.dto;

import java.util.List;

/**
 * 授权代次视图。
 *
 * @param generationId   代次主键
 * @param experimentId   实验编号
 * @param generationNo   实验内代次序号，从1递增
 * @param status         ACTIVE / SUPERSEDED
 * @param effectiveAt    生效时间，Unix 毫秒 UTC
 * @param supersededAt   被取代时间，Unix 毫秒 UTC；null 表示仍活动
 * @param roster         该代次角色名册
 * @param grantedFields  每个角色获得的最小字段
 */
public record GenerationView(
        long generationId,
        String experimentId,
        int generationNo,
        String status,
        long effectiveAt,
        Long supersededAt,
        RosterView roster,
        java.util.Map<String, List<String>> grantedFields
) {
}
