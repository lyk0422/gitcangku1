package com.example.starter.race.api;

import com.example.starter.race.domain.AdvancementListStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 晋级名单响应：生成成功与查询生效名单共用。
 *
 * <p>并列跨过 Q/W 边界时同名次全部纳入，实际人数可超过 组数×Q+W，
 * 此时 {@code overQuotaReasons} 非空，逐项说明超额来源。
 *
 * @param advancementKey    全局唯一名单键
 * @param raceId            赛事ID
 * @param version           名单生成后的赛事版本号
 * @param status            ACTIVE / REVOKED
 * @param directQuota       每组直接晋级名额 Q
 * @param wildcardQuota     跨组补位名额 W
 * @param generatedAt       名单生成时刻，Unix毫秒时间戳
 * @param revokedAt         撤销时刻，Unix毫秒时间戳；生效中为 null
 * @param directCount       实际直接晋级人数（含并列超额）
 * @param wildcardCount     实际补位人数（含并列超额）
 * @param advancedCount     实际晋级总人数
 * @param expectedCount     名义名额数 = 组数×Q+W
 * @param overQuotaReasons  并列超额原因；无超额时为空列表
 * @param entries           固化的晋级条目（先按分组的 DIRECT，再按全局成绩的 WILDCARD）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdvancementResponse(
        String advancementKey,
        String raceId,
        int version,
        AdvancementListStatus status,
        int directQuota,
        int wildcardQuota,
        long generatedAt,
        Long revokedAt,
        int directCount,
        int wildcardCount,
        int advancedCount,
        int expectedCount,
        List<OverQuotaReason> overQuotaReasons,
        List<AdvancementEntryResponse> entries
) {

    /**
     * 并列超额原因：边界处（组内第 Q 名或全局第 W 名）出现并列，同名次全部纳入。
     *
     * @param boundary    DIRECT-组内直接晋级边界，WILDCARD-全局补位边界
     * @param groupCode   DIRECT 边界所属分组代码；WILDCARD 边界为 null
     * @param quota       该边界的名额数（Q 或 W）
     * @param tiedTimeMs  发生并列的总耗时（毫秒）
     * @param bibs        因并列被一并纳入的全部参赛号，按参赛号字典序
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OverQuotaReason(
            String boundary,
            String groupCode,
            int quota,
            long tiedTimeMs,
            List<String> bibs
    ) {
    }
}
