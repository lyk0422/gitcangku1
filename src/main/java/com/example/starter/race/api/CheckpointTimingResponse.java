package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分段通过记录响应。
 *
 * @param timingId        全局唯一分段记录ID
 * @param bib             选手参赛号
 * @param checkpointCode  检查点代码
 * @param position        检查点顺序
 * @param elapsedMillis   通过该检查点的累计耗时（毫秒）
 * @param excluded        是否被排除出排名：true-医疗暂停生效期间提交
 * @param exclusionReason 排除原因（MEDICAL_HOLD）；未排除为 null
 * @param holdId          导致排除的生效医疗暂停ID；未排除为 null
 * @param createdAt       提交时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long elapsedMillis,
        boolean excluded,
        String exclusionReason,
        String holdId,
        long createdAt
) {

    /** 兼容旧调用的构造器：未排除分段。 */
    public CheckpointTimingResponse(
            String timingId,
            String bib,
            String checkpointCode,
            int position,
            long elapsedMillis,
            long createdAt) {
        this(timingId, bib, checkpointCode, position, elapsedMillis, false, null, null, createdAt);
    }
}
