package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 限速重排记录视图：重排记录主档 + 逐段明细 + 限速令快照。
 * 历史证据，生成后不可变；createdAt 为 UTC 毫秒时间戳。
 */
public record RearrangementView(long id, String scheduleKey, String opType, long shiftMinutes,
                                long createdAt, List<RearrangementItemView> items,
                                List<RearrangementRestrictionView> restrictions) {
}
