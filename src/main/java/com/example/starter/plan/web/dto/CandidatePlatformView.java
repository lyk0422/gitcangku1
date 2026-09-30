package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 调度员候选站台评估项：对某站台给出当前有效能力是否适配编组与申请时段。
 *
 * @param platformId          站台 ID
 * @param compatible          长度、制式、无障碍三项是否全部满足
 * @param available           各项能力满足且申请时段内与其他已发布占用无重叠
 * @param reasons             不适配/不可用原因代码列表（兼容但窗口冲突时含 WINDOW_CONFLICT），可用为空
 * @param version             评估所用站台能力版本
 * @param maxLengthM          站台长度上限，单位米
 * @param supportedPowerTypes 站台兼容供电制式代码列表
 * @param accessible          站台是否具备无障碍登乘能力
 * @param conflictingBookings 与申请时段重叠的生效占用明细
 */
public record CandidatePlatformView(String platformId, boolean compatible, boolean available,
                                    List<String> reasons, int version, int maxLengthM,
                                    List<String> supportedPowerTypes, boolean accessible,
                                    List<ConflictingBookingView> conflictingBookings) {

    /**
     * 窗口冲突明细。
     *
     * @param scheduleKey 冲突来源计划业务键
     * @param trainNo     冲突列车编号
     * @param startUtc    冲突占用开始时刻（含），UTC
     * @param endUtc      冲突占用结束时刻（不含），UTC
     */
    public record ConflictingBookingView(String scheduleKey, String trainNo,
                                         Instant startUtc, Instant endUtc) {
    }
}
