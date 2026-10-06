package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.GroupMember;

/**
 * 组成员任务视图。
 */
public record GroupMemberView(String deviceId, String status, int prepareGeneration,
                              Integer activationGeneration) {

    public static GroupMemberView of(GroupMember member) {
        return new GroupMemberView(member.deviceId(), member.status().name(),
                member.prepareGeneration(), member.activationGeneration());
    }
}
