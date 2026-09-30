package com.example.starter.salinity.dto;

import java.time.Instant;
import java.util.List;

/**
 * 封存结果。
 *
 * @param voyageCode    航次编号
 * @param sealedAtUtc   封存固化时刻（UTC）
 * @param sealedProfile 固化后的剖面，按站位、深度升序
 */
public record SealResult(String voyageCode,
                         Instant sealedAtUtc,
                         List<SealedProfileView> sealedProfile) {
}
