package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 预览冲突明细（创建交换单返回 200/201，body 内携带，不抛错）。
 *
 * @param type                    冲突类型：INTERNAL_TARGET_CONFLICT 参与计划目标互相重叠 /
 *                                TRAIN_OVERLAP 同一计划内同车时间重叠 /
 *                                SECTION_CONFLICT 与未参与的已发布计划区段冲突
 * @param sectionId               冲突区段 ID
 * @param scheduleKey             发起冲突的参与计划业务键
 * @param conflictingScheduleKey  对端计划业务键（内部冲突为另一参与计划；外部冲突为未参与计划）
 * @param trainNo                 冲突目标占用的列车编号
 * @param startUtc                冲突目标占用开始（含），UTC
 * @param endUtc                  冲突目标占用结束（不含），UTC
 */
public record SwapConflictView(String type, String sectionId, String scheduleKey,
                               String conflictingScheduleKey, String trainNo,
                               Instant startUtc, Instant endUtc) {
}
