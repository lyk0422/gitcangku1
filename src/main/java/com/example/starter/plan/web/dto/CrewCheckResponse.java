package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划乘务门禁预检/查询结果：行程窗口、线路等级要求与逐人资格覆盖、占用窗口、冲突计划。
 *
 * @param scheduleKey    被检查的计划业务键
 * @param lineId         行程所属线路 ID
 * @param requiredGrade  行程要求的最低线路等级（取各区段要求最大值）
 * @param tripStartUtc   行程左闭右开窗口起点（含），UTC，取全部占用最小起点
 * @param tripEndUtc     行程左闭右开窗口终点（不含），UTC，取全部占用最大终点
 * @param crew           逐人结果，按 memberKey 升序稳定排列
 */
public record CrewCheckResponse(String scheduleKey, String lineId, int requiredGrade,
                                Instant tripStartUtc, Instant tripEndUtc,
                                List<CrewCoverageView> crew) {
}
