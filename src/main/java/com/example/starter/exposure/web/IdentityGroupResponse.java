package com.example.starter.exposure.web;

import java.util.List;

/**
 * 归并组视图。
 *
 * @param groupId      归并组编号
 * @param version      当前乐观锁版本
 * @param members      组内全部成员编号，按编号升序
 * @param updatedAtUtc 最近一次归并时刻，epoch 毫秒，UTC
 */
public record IdentityGroupResponse(
        String groupId,
        int version,
        List<String> members,
        long updatedAtUtc
) {
}
