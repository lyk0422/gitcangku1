package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 泊位静态信息。
 *
 * @param id           泊位主键
 * @param code         泊位编码，对外使用
 * @param name         泊位名称
 * @param staticDepthM 泊位静态水深，单位米（相对同一潮高基准面）
 */
public record Berth(Long id, String code, String name, BigDecimal staticDepthM) {
}
