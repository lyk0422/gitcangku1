package com.example.starter.observation.geofence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 离线观测批量提交响应：数量与比例为实际值；明细顺序与请求条目一致。
 *
 * @param requestId      批量请求标识
 * @param operator       提交操作者标识
 * @param totalCount     实际提交条数
 * @param acceptedCount  实际接受条数（含无生效围栏的接受）
 * @param rejectedCount  实际围栏拒绝条数
 * @param acceptedRatio  接受比例实际值（acceptedCount / totalCount，4 位小数）
 * @param submittedAtUtc 服务端接收提交时刻（UTC，ISO-8601）
 * @param items          各条目匹配结果明细，顺序与请求一致
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchObservationResponse(
        String requestId,
        String operator,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        BigDecimal acceptedRatio,
        Instant submittedAtUtc,
        List<Item> items) {

    /**
     * 单条观测的匹配结果明细。
     *
     * @param observationId       客户端观测唯一标识
     * @param siteId              采集站点标识
     * @param matchResult         落库时的围栏匹配结果：ACCEPTED / GEOFENCE_REJECTED
     * @param status              当前状态（提交时与 matchResult 相同）
     * @param matchedFenceId      匹配到的围栏标识；无生效围栏时为 null
     * @param matchedFenceVersion 匹配到的围栏版本号；无生效围栏时为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String observationId,
            String siteId,
            String matchResult,
            String status,
            String matchedFenceId,
            Integer matchedFenceVersion) {
    }
}
