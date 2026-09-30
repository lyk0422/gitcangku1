package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 乘务门禁预检请求：给定乘务组，按当前资格与已发布占用评估逐人覆盖结果。
 */
public record CrewCheckRequest(
        @NotEmpty @Size(max = 8) @Valid List<CrewMemberItem> crew) {
}
