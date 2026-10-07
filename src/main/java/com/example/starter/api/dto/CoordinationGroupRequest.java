package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 协调组登记/修订请求：同一 groupId 重复登记视为整体修订替换。
 *
 * <p>成员 2 至 4 个不同制品名；允许元组 1 至 5 条，每条完整列出组内全部成员版本。
 * 成员列表与元组列表的顺序不影响请求身份（换序同参）。
 */
public record CoordinationGroupRequest(
        @NotBlank String groupId,
        @NotBlank String operator,
        @Size(min = 2, max = 4) List<@NotBlank String> members,
        @Size(min = 1, max = 5) List<@Valid List<@Valid TupleEntrySpec>> tuples,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {

    public CoordinationGroupRequest {
        if (members != null) {
            members = List.copyOf(members);
        }
        if (tuples != null) {
            tuples = tuples.stream()
                    .map(t -> t == null ? null : List.copyOf(t))
                    .toList();
        }
    }
}
