package com.example.starter.film.service;

import com.example.starter.film.model.SegmentVersion;

/**
 * 片段提交结果。
 *
 * @param version              本次（或幂等重放的）片段版次
 * @param expiredReviewCount   本次提交导致过期的未决审核数量
 */
public record SegmentSubmitOutcome(
        SegmentVersion version,
        int expiredReviewCount
) {
}
