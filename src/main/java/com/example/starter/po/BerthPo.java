package com.example.starter.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泊位持久化对象；staticDepth 为静态水深（米）。
 */
public record BerthPo(
        Long id,
        String code,
        BigDecimal staticDepth,
        LocalDateTime createdAt) {
}
