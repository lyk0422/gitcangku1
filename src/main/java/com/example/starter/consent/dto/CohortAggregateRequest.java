package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 最小群体汇总请求：3-10 个不同主体，仅研究用途；集合换序视为同参。
 *
 * @param requestId 幂等请求标识
 * @param purpose   用途，仅支持 RESEARCH 研究
 * @param members   成员列表，3-10 个不同主体，每主体一项
 */
public record CohortAggregateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Purpose purpose,
        @NotNull @Size(min = 3, max = 10) List<@Valid CohortMember> members) {
}
