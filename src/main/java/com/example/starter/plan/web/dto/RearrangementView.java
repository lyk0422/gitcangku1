package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划重排记录视图：整体顺延分钟数与逐段明细。记录不可变，历史查询原样返回。
 */
public record RearrangementView(long rearrangementId, String opType, long shiftMinutes,
                                List<RearrangementSegmentView> segments) {
}
