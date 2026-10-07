package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 协调组登记/修订请求：成员 2 至 4 个不同制品名，允许元组 1 至 5 条，
 * 每条元组必须完整列出全部成员版本。成员与元组列表的书写顺序不影响参数身份。
 *
 * @param groupName                  协调组名称，全仓库唯一；同名再次登记视为修订
 * @param operator                   非空操作者标识，参与幂等身份
 * @param members                    成员制品名称列表
 * @param allowedTuples              允许版本元组列表，每条为 成员名 -> 允许版本
 * @param expectedRepositoryVersion  期望的仓库版本号，不匹配返回 409 且不改变规则
 */
public record CoordinationGroupRequest(
        @NotBlank String groupName,
        @NotBlank String operator,
        @NotEmpty @Size(max = 4) List<@NotBlank String> members,
        @NotEmpty @Size(max = 5) List<@NotNull Map<@NotBlank String, @NotNull @Positive Integer>> allowedTuples,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
