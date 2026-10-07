package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 协调组：必须按厂商验证的允许版本元组一起发行的制品集合。
 *
 * <p>锁定语义：锁图（依赖可达的名称集合）中包含本组至少 2 名成员时，
 * 这些成员被选中的版本必须是同一允许元组的投影；不足 2 名成员时不产生约束，
 * 也不会因此把其余成员拉入锁图。成员版本号之间不要求相等。
 *
 * @param name          协调组名称，全仓库唯一
 * @param revision      组内修订号，从 1 开始，每次修订加一
 * @param members       成员制品名称（2 至 4 个，按名称升序）
 * @param allowedTuples 允许版本元组（1 至 5 条），每条完整覆盖全部成员：成员名 -> 允许版本
 */
public record CoordinationGroup(
        String name,
        int revision,
        List<String> members,
        List<Map<String, Integer>> allowedTuples) {

    public CoordinationGroup {
        members = List.copyOf(members);
        allowedTuples = allowedTuples.stream()
                .map(tuple -> Map.copyOf(tuple))
                .toList();
    }
}
