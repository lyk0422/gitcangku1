package com.example.starter.blind.dto;

import java.util.List;

/**
 * 中心负责人视角的启用版本视图：可查看版本状态、双人确认留痕与各区组名额/用量。
 * 仅中心负责人可查看；不含处理代码与席位序号。
 *
 * @param requestId          启用申请（配置版本）编号
 * @param siteId             中心编号
 * @param version            配置版本号，从 1 开始递增
 * @param status             PENDING / ACTIVATED
 * @param leadActorId        提交配置的中心负责人
 * @param leadConfirmed      负责人是否已确认
 * @param leadConfirmedAt    负责人确认时间，Unix 毫秒，UTC；null 表示未确认
 * @param reviewerActorId    独立复核人操作者编号；null 表示尚未确认
 * @param reviewerConfirmed  独立复核人是否已确认
 * @param reviewerConfirmedAt 复核人确认时间，Unix 毫秒，UTC；null 表示未确认
 * @param createdAt          版本提交时间，Unix 毫秒，UTC
 * @param activatedAt        启用时间，Unix 毫秒，UTC；null 表示未启用
 * @param blocks             区组名额与用量（仅负责人视角包含 quota/used）
 */
public record SiteActivationView(
        String requestId,
        String siteId,
        int version,
        String status,
        String leadActorId,
        boolean leadConfirmed,
        Long leadConfirmedAt,
        String reviewerActorId,
        boolean reviewerConfirmed,
        Long reviewerConfirmedAt,
        long createdAt,
        Long activatedAt,
        List<BlockQuotaView> blocks
) {

    /**
     * 负责人视角区组名额明细。
     *
     * @param blockNo   区组号，从 1 开始
     * @param quota     该版本申请名额
     * @param frozen    已冻结量；已启用版本等于 quota，未启用为 0
     * @param used      该冻结版本下已分配量
     * @param remaining 剩余可分配量 = frozen - used；未启用版本为 0
     */
    public record BlockQuotaView(
            int blockNo,
            int quota,
            int frozen,
            int used,
            int remaining
    ) {
    }
}
