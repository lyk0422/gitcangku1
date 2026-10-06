package com.example.starter.firmware.domain;

/**
 * 组发布成员任务，同设备同组最多一条。
 *
 * @param id            成员任务ID
 * @param groupId       所属组发布ID
 * @param deviceId      设备ID
 * @param generation    组代次，创建时取自组当前代次，回执必须匹配
 * @param status        成员状态
 * @param preparedAtUtc 暂存时刻，UTC，ISO-8601格式；未暂存为 null
 */
public record GroupMember(long id, long groupId, String deviceId, int generation,
                          GroupMemberStatus status, String preparedAtUtc) {
}
