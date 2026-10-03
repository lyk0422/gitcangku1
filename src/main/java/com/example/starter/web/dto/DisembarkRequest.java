package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 离轮确认请求，携带实际离轮时间（UTC）。
 */
public record DisembarkRequest(@NotNull Instant disembarkedAtUtc) {
}