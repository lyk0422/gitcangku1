package com.example.starter.plan.model;

import java.math.BigDecimal;

/**
 * 区段防护档案。
 *
 * @param id        主键
 * @param sectionId 区段 ID，全局唯一
 * @param protectD  防护距离 d，米，正十进制、最多 3 位小数
 * @param version   配置版本，创建为 1，每次成功修改加一
 */
public record SectionProfile(long id, String sectionId, BigDecimal protectD, int version) {
}
