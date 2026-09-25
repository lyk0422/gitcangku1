package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 乘务员资质响应：覆盖区段按规范化（升序）顺序返回。
 */
public record QualificationResponse(
        String crewId,
        String qualificationCode,
        List<String> sections,
        Instant expiresAtUtc,
        boolean terminated,
        int version) {
}
