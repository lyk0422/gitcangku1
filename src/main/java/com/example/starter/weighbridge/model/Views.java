package com.example.starter.weighbridge.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询视图模型集合：与表中文 COMMENT 同步，重量单位公斤，时间 Asia/Shanghai。
 */
public final class Views {

    private Views() {
    }

    /** 运输单视图：orderNo 业务唯一，vehicleNo 为合成车辆号。 */
    public record OrderView(long id, String orderNo, String vehicleNo, LocalDateTime createdAt) {
    }

    /** 称量单视图：phase 见 Phase；disputeId 为复称来源争议，原始称量为 null。 */
    public record WeighingView(long id, long transportOrderId, Long disputeId, String phase,
                               int weightKg, LocalDateTime weighedAt, String requestId) {
    }

    /** 争议视图：status 为 OPEN/ADJUDICATED。 */
    public record DisputeView(long id, long transportOrderId, String reason, String status,
                              LocalDateTime createdAt, AdjudicationView adjudication) {
    }

    /** 裁决视图：adoptedWeighingId 指向被采纳称量，理由与裁决员留痕。 */
    public record AdjudicationView(long id, long disputeId, long adoptedWeighingId, String reason,
                                   String decidedBy, LocalDateTime decidedAt) {
    }

    /** 冻结快照视图：netWeightKg = grossWeightKg - tareWeightKg；weighingVersion 为冻结时已提交称量数。 */
    public record FreezeView(long id, long transportOrderId, int tareWeightKg, long adoptedWeighingId,
                             int grossWeightKg, int netWeightKg, int weighingVersion,
                             LocalDateTime frozenAt) {
    }

    /** 勘误申请视图：冻结后新称量的唯一去向，不覆盖快照。 */
    public record ErrataView(long id, long freezeId, int weightKg, LocalDateTime weighedAt,
                             String reason, String status, LocalDateTime createdAt) {
    }

    /** 运输单总览：全部称量、争议（含裁决）、冻结快照与勘误。 */
    public record OrderOverview(OrderView order, List<WeighingView> weighings,
                                List<DisputeView> disputes, FreezeView freeze,
                                List<ErrataView> erratas) {
    }
}