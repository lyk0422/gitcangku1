package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 协调组视图：成员与允许元组均按名称升序规范化返回。
 *
 * @param repositoryVersion 该次登记/修订完成后的仓库版本号
 */
public record CoordinationGroupResponse(
        String groupId,
        String operator,
        List<String> members,
        List<List<LockEntryResponse>> tuples,
        long repositoryVersion,
        Instant createdAt) {
}
