package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 限制区段在发布快照中的开放清单片段（固化）。
 */
public record SnapshotSectionView(String sectionId, int version, List<String> allowedClasses) {
}
