package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 发布详情视图（授权证据查询）：首次发布快照 + 全部后续更正附页。
 *
 * @param release 首次发布快照（不可变）
 * @param addenda 发布后更正附页（按版本号升序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReleaseDetailResponse(
        ReleaseResponse release,
        List<AddendumView> addenda) {

    /**
     * 更正附页视图。
     *
     * @param addendumId   附页标识
     * @param version      对应观测版本号
     * @param deleted      该版本是否为删除墓碑
     * @param location     该版本观测地点（墓碑时不返回）
     * @param reading      该版本观测读数（墓碑时不返回）
     * @param note         该版本观测备注（墓碑时不返回）
     * @param operation    产生该附页的操作类型
     * @param requestId    产生该附页的请求标识
     * @param createdAtUtc 附页生成时刻（UTC，ISO-8601）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddendumView(
            String addendumId,
            int version,
            boolean deleted,
            String location,
            String reading,
            String note,
            String operation,
            String requestId,
            Instant createdAtUtc) {

        public static AddendumView of(ReleaseAddendumRecord record) {
            return new AddendumView(record.addendumId(), record.version(), record.deleted(),
                    record.deleted() ? null : record.location(),
                    record.deleted() ? null : record.reading(),
                    record.deleted() ? null : record.note(),
                    record.operation(), record.requestId(), record.createdAtUtc());
        }
    }
}
