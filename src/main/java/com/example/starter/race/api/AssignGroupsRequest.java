package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 一次性分组划分请求；成功后赛事版本加一且分组不可改写。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param groups          分组列表：2~8 个分组，每组 2~16 名选手，同一选手只属一个分组
 */
public record AssignGroupsRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotEmpty List<@Valid GroupDefinition> groups
) {

    /**
     * 单个分组定义。
     *
     * @param groupCode 分组代码，赛事内唯一
     * @param bibs      组内选手参赛号，2~16 名
     */
    public record GroupDefinition(
            @NotBlank String groupCode,
            @NotEmpty List<@NotBlank String> bibs
    ) {
    }
}
