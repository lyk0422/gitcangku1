package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 来源策略版本视图，allowedRepos 按字典序升序。
 */
public record PolicyResponse(
        int version,
        int minLevel,
        List<String> allowedRepos,
        Instant createdAt) {
}
