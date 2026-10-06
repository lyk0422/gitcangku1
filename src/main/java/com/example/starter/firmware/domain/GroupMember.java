package com.example.starter.firmware.domain;

/**
 * 组发布成员任务，同设备同组发布单最多一条。
 *
 * @param id                   组成员任务ID
 * @param groupId              所属组发布单ID
 * @param deviceId             设备ID
 * @param status               成员状态
 * @param prepareGeneration    成员任务所属准备代次，创建时固定
 * @param activationGeneration 成员激活代次，激活时与组代次一致，未激活为 null
 */
public record GroupMember(long id, long groupId, String deviceId, GroupMemberStatus status,
                          int prepareGeneration, Integer activationGeneration) {
}
