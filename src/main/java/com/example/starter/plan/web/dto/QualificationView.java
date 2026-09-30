package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 乘务资格版本视图（含已换发/已撤销的历史版本）。
 *
 * @param validToUtc 资格失效时刻（不含），UTC；null 表示长期有效
 * @param revokedAt  撤销时刻，UTC 毫秒；未撤销为 null
 */
public record QualificationView(String memberKey, String lineId, int version, int gradeLevel,
                                Instant validFromUtc, Instant validToUtc, String status,
                                Long revokedAt) {
}
