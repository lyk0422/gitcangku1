package com.example.starter.consent.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.example.starter.consent.Purpose;

/**
 * 固定授权代次的原子批量查询请求：整个请求只有一个用途，包含 1～50 个查询项。
 *
 * <p>同一主体在本请求中只能指定一个 epoch；（主体、代次、记录键）三元组不得重复。
 *
 * @param requestId 幂等请求标识，同一 requestId 相同参数重放保持首次结果
 * @param purpose   全批唯一用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param items     查询项列表，数量 1～50，成功响应顺序与本列表一致
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Purpose purpose,
        @NotNull @Size(min = 1, max = 50) @Valid List<@NotNull BatchQueryItem> items) {
}
