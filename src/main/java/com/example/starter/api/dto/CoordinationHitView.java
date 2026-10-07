package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个协调组的命中依据视图。
 *
 * @param groupId 被触发的协调组业务标识
 * @param tuple   裁决时命中的完整允许元组（按名称升序）
 */
public record CoordinationHitView(
        String groupId,
        List<LockEntryResponse> tuple) {
}
