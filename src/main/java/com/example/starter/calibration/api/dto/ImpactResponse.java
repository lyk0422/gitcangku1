package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 失效影响响应：单一 impactVersion 下的全部冻结明细，只读且可按 impactVersion 重现。
 *
 * @param impactVersion  影响版本号
 * @param invalidationKey 失效单业务键
 * @param rootStandardId 失效根标准器版本业务键
 * @param invalidFrom    失效起始时刻（UTC）
 * @param activatedAt    激活时间（UTC）
 * @param items          冻结明细（按测量键升序）
 */
public record ImpactResponse(
        String impactVersion,
        String invalidationKey,
        String rootStandardId,
        Instant invalidFrom,
        Instant activatedAt,
        List<ImpactItemDto> items) {
}
