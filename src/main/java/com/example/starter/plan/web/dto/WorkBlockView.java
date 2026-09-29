package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 封锁视图：区段维护封锁窗口，UTC 左闭右开。
 */
public record WorkBlockView(String blockKey, LocalDate opDate, String sectionId,
                            Instant startUtc, Instant endUtc, String status) {
}
