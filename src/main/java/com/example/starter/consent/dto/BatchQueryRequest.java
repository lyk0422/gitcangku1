package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 固定授权代次原子批量查询请求：一个用途对应 1～50 个查询项。
 *
 * @param requestId 幂等请求标识，同一 requestId 相同参数重放返回首次快照
 * @param purpose   用途：RESEARCH 研究 / PERSONALIZATION 个性化，用途之间相互隔离
 * @param items     查询项，返回顺序与本列表顺序一致
 */
public record BatchQueryRequest(
        @jakarta.validation.constraints.NotBlank @Size(max = 128) String requestId,
        @NotNull Purpose purpose,
        @NotEmpty @Size(min = 1, max = 50) List<@Valid BatchQueryItem> items) {
}
