package com.example.starter.well;

import java.time.Instant;

/**
 * 地热井运行段。
 *
 * @param id 运行段主键
 * @param wellNo 井号
 * @param mode 运行模式：INJECTION=注入，PRODUCTION=采出
 * @param startUtc 段开始时刻（UTC）
 * @param endUtc 段结束时刻（UTC），null 表示仍在运行
 */
public record WellSegment(long id, String wellNo, String mode, Instant startUtc, Instant endUtc) {

    public static final String MODE_INJECTION = "INJECTION";
    public static final String MODE_PRODUCTION = "PRODUCTION";

    public boolean isOpen() {
        return endUtc == null;
    }
}