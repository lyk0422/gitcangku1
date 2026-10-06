package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.GroupMember;

/**
 * 组成员视图。
 */
public record GroupMemberView(String deviceId, int generation, String status, String preparedAtUtc) {

    public static GroupMemberView of(GroupMember member) {
        return new GroupMemberView(member.deviceId(), member.generation(), member.status().name(),
                member.preparedAtUtc());
    }
}
