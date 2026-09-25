package com.example.starter.work.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 生效施工窗口视图，用于按区段查询施工窗口。
 */
public record WorkWindowView(
        String workKey,
        Instant startUtc,
        Instant endUtc,
        List<String> sectionIds) {
}
