package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 协调组不可变快照：一组制品只允许以厂商验证的版本元组一起发行。
 *
 * <p>锁图包含组中至少 2 名成员时，所选这些成员版本的组合必须等于
 * 某条允许元组在这些成员上的投影；只涉及 0 或 1 名成员时不受约束，
 * 组约束本身不会把组内其他成员引入锁图。
 *
 * @param groupId 协调组业务标识
 * @param members 成员制品名称（名称升序，2 至 4 个不同名称）
 * @param tuples  允许版本元组列表；每条元组为 成员名 -> 精确版本 的完整映射
 */
public record CoordinationGroup(
        String groupId,
        List<String> members,
        List<Map<String, Integer>> tuples) {
}
