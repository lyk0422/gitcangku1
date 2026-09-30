package com.example.starter.salinity.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 勘误说明：封存后仅可追加，不修改冻结剖面，原剖面继续可查。
 *
 * @param id                    勘误主键
 * @param voyageCode            所属航次编号
 * @param station               站位编号
 * @param depthM                勘误对应深度，单位米
 * @param sealedProfileId       被勘误的原封存剖面条目主键
 * @param correctedSalinityPsu  更正后盐度，单位 PSU；{@code null} 表示纯文字勘误
 * @param note                  更正说明正文
 * @param requestId             勘误请求幂等键，全局唯一
 * @param createdAtUtc          勘误追加时刻（UTC）
 */
public record Correction(Long id,
                         String voyageCode,
                         String station,
                         Integer depthM,
                         Long sealedProfileId,
                         BigDecimal correctedSalinityPsu,
                         String note,
                         String requestId,
                         Instant createdAtUtc) {
}
