package com.example.starter.plan.model;

import java.time.Instant;
import java.util.List;

/**
 * 乘务资质主记录。
 *
 * @param id         主键
 * @param qualCode   资质代码，全局唯一
 * @param crewId     乘务员标识（司机或车长）
 * @param sections   覆盖区段集合（已规范化：排序去重，换序视为同参）
 * @param expiresUtc 资质到期时刻（UTC）；必须严格晚于计划终到时刻方为有效
 * @param version    资质版本，创建为 1，每次修改加一
 * @param terminated 是否已提前终止，终止不可逆
 */
public record CrewQualification(long id, String qualCode, String crewId, List<String> sections,
                                Instant expiresUtc, int version, boolean terminated) {
}
