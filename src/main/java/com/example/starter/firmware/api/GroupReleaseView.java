package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.GroupRelease;

import java.util.List;

/**
 * 组发布单视图，含全部成员任务。
 */
public record GroupReleaseView(long groupId, int version, String model, String toVersion,
                               String status, int prepareGeneration, Integer activationGeneration,
                               String failedDeviceId, String activatedAtUtc,
                               List<GroupMemberView> members) {

    public static GroupReleaseView of(GroupRelease group, List<GroupMemberView> members) {
        return new GroupReleaseView(group.id(), group.version(), group.model(), group.toVersion(),
                group.status().name(), group.prepareGeneration(), group.activationGeneration(),
                group.failedDeviceId(), group.activatedAtUtc(), members);
    }
}
