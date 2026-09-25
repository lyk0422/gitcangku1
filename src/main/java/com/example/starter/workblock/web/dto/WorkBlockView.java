package com.example.starter.workblock.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 施工占用窗口视图，含规范化（字典序）后的区段集合。
 */
public record WorkBlockView(String workKey, int version, String status,
                            Instant startUtc, Instant endUtc,
                            List<String> sectionIds, String operator,
                            Long cancelledAt) {
}
