package com.example.starter.film.api;

import com.example.starter.film.domain.MasterStatus;

import java.time.Instant;
import java.util.List;

/**
 * 母版视图，用于查询。
 */
public record MasterView(String masterNo, MasterStatus status, List<MasterSegmentView> segments,
                         Instant createdAt, Instant revokedAt) {
}
