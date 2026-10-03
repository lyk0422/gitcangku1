package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 登记气象限速令请求。requestKey 为幂等键；operator 为操作者标识。
 * sectionId、时段与速度的业务合法性（区段非空、时段左闭右开、速度 10~300km/h）由服务层校验并返回 422。
 */
public record RestrictionCreateRequest(
        @NotBlank String requestKey,
        @NotBlank String restrictionKey,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        Integer maxSpeedKmh,
        @NotBlank String operator) {
}