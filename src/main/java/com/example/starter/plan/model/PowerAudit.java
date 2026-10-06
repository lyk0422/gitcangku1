package com.example.starter.plan.model;

import java.time.LocalDate;

/**
 * 功率裁决快照（不可变）。发布、改签、取消、绑定变更与上限调整共同裁决时追加，
 * 冻结当时的上限版本、上限值与裁决后功率切片，历史不漂移。
 *
 * @param id            主键，单调递增即裁决顺序
 * @param opType        裁决来源：PUBLISH / RESCHEDULE / CANCEL / BIND / CAP_CHANGE
 * @param substationKey 被裁决变电所业务键
 * @param opDate        功率切片所属运营日（Asia/Shanghai）；无在效占用时为 null
 * @param capVersion    裁决时的上限版本快照
 * @param capKw         裁决时的功率上限快照，千瓦
 * @param slicesJson    裁决后恒定区间功率切片 JSON
 * @param createdAt     快照创建时刻，UTC 毫秒
 */
public record PowerAudit(long id, String opType, String substationKey, LocalDate opDate,
                         int capVersion, int capKw, String slicesJson, long createdAt) {
}
