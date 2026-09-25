package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 资质缺口诊断条目：稳定列出角色与缺口。
 *
 * @param role            涉及角色：DRIVER / CONDUCTOR / BOTH（两角色同一人时）
 * @param crewId          涉及乘务员 id
 * @param gapType         缺口类型：SAME_PERSON 两角色同一人 / QUALIFICATION_MISSING 无有效资质 /
 *                        SECTION_COVERAGE 区段覆盖不足 / EXPIRED 到期时刻未严格晚于计划终到
 * @param missingSections 未覆盖或未满足到期要求的区段（升序），SAME_PERSON 时为空
 */
public record CrewGapView(String role, String crewId, String gapType, List<String> missingSections) {
}
