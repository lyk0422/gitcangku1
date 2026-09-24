package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 分组划分请求：OPEN 赛事生成名单前一次性把选手划入 2~8 个分组，
 * 每组 2~16 人，同一选手只属一个分组；成功后版本加一且不可改写。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param groups          分组定义，顺序即分组顺序（position 从1连续分配）
 */
public record AssignGroupsRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotEmpty @Valid List<GroupDefinition> groups
) {

    /**
     * 单个分组定义。
     *
     * @param groupCode 分组代码，赛事内唯一
     * @param members   成员参赛号列表，每组 2~16 人且跨组不重复
     */
    public record GroupDefinition(
            @NotBlank String groupCode,
            @NotEmpty List<@NotBlank String> members
    ) {
    }
}
