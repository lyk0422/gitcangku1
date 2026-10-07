package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 协调组视图：成员按名称升序，元组按规范化顺序返回。
 *
 * @param groupName         协调组名称
 * @param revision          组内修订号，从 1 开始
 * @param repositoryVersion 该次登记/修订完成后的仓库版本号
 * @param members           成员制品名称（升序）
 * @param allowedTuples     允许版本元组，每条完整覆盖全部成员
 * @param updatedAt         本次登记/修订时间，UTC
 */
public record CoordinationGroupResponse(
        String groupName,
        int revision,
        long repositoryVersion,
        List<String> members,
        List<Map<String, Integer>> allowedTuples,
        Instant updatedAt) {
}
