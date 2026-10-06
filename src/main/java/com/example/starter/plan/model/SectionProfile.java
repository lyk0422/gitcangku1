package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 区段防护档案。
 *
 * @param id                  主键
 * @param sectionId           区段 ID，全局唯一
 * @param protectionDistanceM 区段防护距离 d，米，正十进制、最多3位小数
 * @param version             档案版本，从 1 开始，每次修改加一
 */
public record SectionProfile(long id, String sectionId, BigDecimal protectionDistanceM,
                             int version) {
}
