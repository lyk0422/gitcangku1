package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.GroupMember;
import com.example.starter.firmware.domain.GroupRelease;

import java.util.List;

/**
 * 组发布视图：含当前代次、状态、激活代次、成败统计、失败成员与成员明细。
 */
public record GroupReleaseView(long groupId, String model, String toVersion, int generation,
                               String status, Integer activationGeneration,
                               int successCount, int failedCount, String failedDevice,
                               List<GroupMemberView> members) {

    public static GroupReleaseView of(GroupRelease group, List<GroupMember> members) {
        return new GroupReleaseView(group.id(), group.model(), group.toVersion(), group.generation(),
                group.status().name(), group.activationGeneration(), group.successCount(),
                group.failedCount(), group.failedDevice(),
                members.stream().map(GroupMemberView::of).toList());
    }
}
