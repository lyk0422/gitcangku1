package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 发布快照：固化发布时刻的编组、区段路径、配置版本与校验结论，追加后不可变。
 *
 * @param id               主键
 * @param planId           被发布计划 id
 * @param scheduleKey      被发布计划业务键
 * @param opDate           运营日期（Asia/Shanghai 日历日）
 * @param operator         操作者标识，历史请求未携带时为 null
 * @param requestKey       本次发布的客户端幂等键
 * @param source           发布来源：PUBLISH / RESCHEDULE / BATCH_RESCHEDULE
 * @param ruleVersion      发布时刻互斥规则集版本
 * @param sectionVersion   发布时刻限制区段配置版本
 * @param formationJson    编组快照 JSON（按车厢位置升序）
 * @param sectionsJson     区段路径快照 JSON（按区段 ID 字典序）
 * @param validationResult 校验结论，固定为 PASSED
 * @param createdAt        快照创建时刻，UTC 毫秒
 */
public record PublishSnapshot(long id, long planId, String scheduleKey, LocalDate opDate,
                              String operator, String requestKey, String source,
                              int ruleVersion, int sectionVersion,
                              String formationJson, String sectionsJson,
                              String validationResult, long createdAt) {
}
