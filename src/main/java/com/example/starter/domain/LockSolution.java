package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 锁定求解结果：精确版本组合与协调组命中依据。
 *
 * @param versions 名称 -> 精确版本（名称升序）
 * @param hits     被触发的协调组命中依据（组 ID 升序），空列表表示未触发任何组
 */
public record LockSolution(
        Map<String, Integer> versions,
        List<CoordinationHit> hits) {

    /**
     * 单个协调组的命中依据：裁决所依据的完整允许元组快照。
     *
     * @param groupId 协调组业务标识
     * @param tuple   命中的允许元组（成员名 -> 精确版本，名称升序）
     */
    public record CoordinationHit(String groupId, Map<String, Integer> tuple) {
    }
}
