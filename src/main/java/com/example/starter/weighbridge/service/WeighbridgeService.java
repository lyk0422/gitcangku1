package com.example.starter.weighbridge.service;

import com.example.starter.weighbridge.domain.DisputeStatus;
import com.example.starter.weighbridge.domain.Phase;
import com.example.starter.weighbridge.model.Views.AdjudicationView;
import com.example.starter.weighbridge.model.Views.DisputeView;
import com.example.starter.weighbridge.model.Views.ErrataView;
import com.example.starter.weighbridge.model.Views.FreezeView;
import com.example.starter.weighbridge.model.Views.OrderOverview;
import com.example.starter.weighbridge.model.Views.OrderView;
import com.example.starter.weighbridge.model.Views.WeighingView;
import com.example.starter.weighbridge.repo.WeighbridgeRepository;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.AdjudicationRow;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.DisputeRow;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.ErrataRow;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.FreezeRow;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.OrderRow;
import com.example.starter.weighbridge.repo.WeighbridgeRepository.WeighingRow;
import com.example.starter.weighbridge.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地磅争议结算业务：称量登记、复称申请、裁决留痕、净重冻结与勘误。
 * 重量单位公斤；复称窗口为出场后 30 分钟；冻结后新称量仅形成勘误申请。
 * 同运输单的写操作通过行锁串行化，保证冻结引用的已提交称量版本集合一致。
 */
@Service
public class WeighbridgeService {

    /** 复称申请窗口：出场毛重过磅后 30 分钟。 */
    public static final long REWEIGH_WINDOW_MINUTES = 30;

    private final WeighbridgeRepository repo;
    private final Clock clock;

    public WeighbridgeService(WeighbridgeRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 创建运输单；orderNo 重复返回 409。 */
    @Transactional
    public OrderView createOrder(String orderNo, String vehicleNo) {
        try {
            long id = repo.insertOrder(orderNo, vehicleNo, now());
            return toView(repo.findOrderById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("运输单号已存在: " + orderNo);
        }
    }

    /** 登记入场皮重或出场毛重；幂等：同 requestId 同参重放，异参 409。 */
    @Transactional
    public WeighingView recordWeighing(long orderId, Phase phase, int weightKg,
                                       LocalDateTime weighedAt, String requestId) {
        if (phase == Phase.REWEIGH_GROSS) {
            throw ApiException.unprocessable("复称毛重请通过争议入口提交");
        }
        String hash = hash("weighing", orderId, phase, weightKg, weighedAt);
        var replayed = repo.findWeighingByRequestId(requestId);
        if (replayed.isPresent()) {
            requireSameParams(replayed.get().requestHash(), hash);
            return toView(replayed.get());
        }
        OrderRow order = repo.lockOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        if (phase == Phase.ENTRY_TARE) {
            if (repo.findWeighing(orderId, Phase.ENTRY_TARE).isPresent()) {
                throw ApiException.conflict("入场皮重已登记");
            }
            if (weighedAt.isBefore(order.createdAt())) {
                throw ApiException.unprocessable("称量时间倒退：早于运输单创建时间");
            }
        } else {
            WeighingRow tare = repo.findWeighing(orderId, Phase.ENTRY_TARE)
                    .orElseThrow(() -> ApiException.unprocessable("无入场称量，不能登记出场毛重"));
            if (repo.findWeighing(orderId, Phase.EXIT_GROSS).isPresent()) {
                throw ApiException.conflict("出场毛重已登记");
            }
            if (weighedAt.isBefore(tare.weighedAt())) {
                throw ApiException.unprocessable("称量时间倒退：早于入场皮重时间");
            }
            if (weightKg < tare.weightKg()) {
                throw ApiException.unprocessable("负净重：毛重小于皮重");
            }
        }
        long id = repo.insertWeighing(orderId, null, phase, weightKg, weighedAt, requestId, hash,
                now());
        return toView(repo.findWeighingById(id).orElseThrow());
    }

    /** 申请复称（开启争议）；出场后 30 分钟外返回 422。 */
    @Transactional
    public DisputeView applyReweigh(long orderId, String reason, String requestId) {
        String hash = hash("reweigh-apply", orderId, reason);
        var replayed = repo.findDisputeByRequestId(requestId);
        if (replayed.isPresent()) {
            requireSameParams(replayed.get().requestHash(), hash);
            return toView(replayed.get(), null);
        }
        repo.lockOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        WeighingRow gross = repo.findWeighing(orderId, Phase.EXIT_GROSS)
                .orElseThrow(() -> ApiException.unprocessable("无出场称量，不能申请复称"));
        if (repo.findFreezeByOrderId(orderId).isPresent()) {
            throw ApiException.conflict("净重已冻结，新称量只能形成勘误申请");
        }
        if (now().isAfter(gross.weighedAt().plusMinutes(REWEIGH_WINDOW_MINUTES))) {
            throw ApiException.unprocessable("逾期争议：超出出场后 30 分钟复称窗口");
        }
        boolean hasOpen = repo.listDisputes(orderId).stream()
                .anyMatch(d -> d.status() == DisputeStatus.OPEN);
        if (hasOpen) {
            throw ApiException.conflict("存在待裁决争议，不能重复申请复称");
        }
        long id = repo.insertDispute(orderId, reason, DisputeStatus.OPEN, now(), requestId, hash);
        return toView(repo.findDisputeById(id).orElseThrow(), null);
    }
    /**
     * 在争议下追加复称毛重；净重已冻结时转为勘误申请（返回 ErrataView），不覆盖快照。
     */
    @Transactional
    public Object submitReweighWeighing(long disputeId, int weightKg, LocalDateTime weighedAt,
                                        String requestId) {
        String hash = hash("reweigh-weighing", disputeId, weightKg, weighedAt);
        var replayedWeighing = repo.findWeighingByRequestId(requestId);
        if (replayedWeighing.isPresent()) {
            requireSameParams(replayedWeighing.get().requestHash(), hash);
            return toView(replayedWeighing.get());
        }
        var replayedErrata = repo.findErrataByRequestId(requestId);
        if (replayedErrata.isPresent()) {
            requireSameParams(replayedErrata.get().requestHash(), hash);
            return toView(replayedErrata.get());
        }
        DisputeRow dispute = repo.findDisputeById(disputeId)
                .orElseThrow(() -> ApiException.notFound("争议不存在: " + disputeId));
        repo.lockOrderById(dispute.transportOrderId()).orElseThrow();
        var freeze = repo.findFreezeByOrderId(dispute.transportOrderId());
        if (freeze.isPresent()) {
            long errataId = repo.insertErrata(freeze.get().id(), weightKg, weighedAt,
                    dispute.reason(), "PENDING", now(), requestId, hash);
            return toView(repo.findErrataByRequestId(requestId).orElseThrow());
        }
        if (dispute.status() != DisputeStatus.OPEN) {
            throw ApiException.conflict("争议已裁决，不能再追加复称");
        }
        WeighingRow tare = repo.findWeighing(dispute.transportOrderId(), Phase.ENTRY_TARE)
                .orElseThrow(() -> ApiException.unprocessable("无入场称量"));
        WeighingRow gross = repo.findWeighing(dispute.transportOrderId(), Phase.EXIT_GROSS)
                .orElseThrow(() -> ApiException.unprocessable("无出场称量"));
        if (weighedAt.isBefore(gross.weighedAt())) {
            throw ApiException.unprocessable("称量时间倒退：早于出场毛重时间");
        }
        if (weightKg < tare.weightKg()) {
            throw ApiException.unprocessable("负净重：复称毛重小于皮重");
        }
        long id = repo.insertWeighing(dispute.transportOrderId(), disputeId, Phase.REWEIGH_GROSS,
                weightKg, weighedAt, requestId, hash, now());
        return toView(repo.findWeighingById(id).orElseThrow());
    }

    /** 裁决：从原称量或复称中采纳一条；并发裁决仅一条生效，其余 409。 */
    @Transactional
    public AdjudicationView adjudicate(long disputeId, long adoptedWeighingId, String reason,
                                       String decidedBy, String requestId) {
        String hash = hash("adjudication", disputeId, adoptedWeighingId, reason, decidedBy);
        var replayed = repo.findAdjudicationByRequestId(requestId);
        if (replayed.isPresent()) {
            requireSameParams(replayed.get().requestHash(), hash);
            return toView(replayed.get());
        }
        DisputeRow dispute = repo.findDisputeById(disputeId)
                .orElseThrow(() -> ApiException.notFound("争议不存在: " + disputeId));
        repo.lockOrderById(dispute.transportOrderId()).orElseThrow();
        if (dispute.status() != DisputeStatus.OPEN) {
            throw ApiException.conflict("争议已裁决，不能重复裁决");
        }
        long orderId = dispute.transportOrderId();
        boolean adoptable = repo.findWeighing(orderId, Phase.EXIT_GROSS)
                .map(w -> w.id() == adoptedWeighingId).orElse(false)
                || repo.listReweighWeighings(disputeId).stream()
                .anyMatch(w -> w.id() == adoptedWeighingId);
        if (!adoptable) {
            throw ApiException.unprocessable("采纳目标必须是原出场毛重或本争议的复称记录");
        }
        long id;
        try {
            id = repo.insertAdjudication(disputeId, adoptedWeighingId, reason, decidedBy, now(),
                    requestId, hash);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("争议已被并发裁决");
        }
        repo.markDisputeAdjudicated(disputeId);
        return toView(repo.findAdjudicationByDisputeId(disputeId)
                .orElseThrow());
    }

    /** 冻结净重：存在未裁决争议返回 409；并发冻结仅一份快照。 */
    @Transactional
    public FreezeView freeze(long orderId, String requestId) {
        String hash = hash("freeze", orderId);
        var replayed = repo.findFreezeByRequestId(requestId);
        if (replayed.isPresent()) {
            requireSameParams(replayed.get().requestHash(), hash);
            return toView(replayed.get());
        }
        repo.lockOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        if (repo.findFreezeByOrderId(orderId).isPresent()) {
            throw ApiException.conflict("净重已冻结，不能重复冻结");
        }
        WeighingRow tare = repo.findWeighing(orderId, Phase.ENTRY_TARE)
                .orElseThrow(() -> ApiException.unprocessable("无入场称量，不能结算"));
        WeighingRow gross = repo.findWeighing(orderId, Phase.EXIT_GROSS)
                .orElseThrow(() -> ApiException.unprocessable("无出场称量，不能结算"));
        List<DisputeRow> disputes = repo.listDisputes(orderId);
        if (disputes.stream().anyMatch(d -> d.status() == DisputeStatus.OPEN)) {
            throw ApiException.conflict("存在未裁决争议，不能结算冻结");
        }
        WeighingRow adopted = gross;
        for (DisputeRow d : disputes) {
            var adj = repo.findAdjudicationByDisputeId(d.id());
            if (adj.isPresent()) {
                adopted = repo.findWeighingById(adj.get().adoptedWeighingId()).orElseThrow();
            }
        }
        int net = adopted.weightKg() - tare.weightKg();
        if (net < 0) {
            throw ApiException.unprocessable("负净重：采纳毛重小于皮重");
        }
        int version = repo.countWeighings(orderId);
        long id;
        try {
            id = repo.insertFreeze(orderId, tare.weightKg(), adopted.id(), adopted.weightKg(),
                    net, version, now(), requestId, hash);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("净重已被并发冻结");
        }
        return toView(repo.findFreezeByOrderId(orderId).orElseThrow());
    }
    /** 运输单总览：全部称量、争议（含裁决留痕）、冻结快照与勘误申请。 */
    @Transactional(readOnly = true)
    public OrderOverview overview(long orderId) {
        OrderRow order = repo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        List<WeighingView> weighings = repo.listWeighings(orderId).stream()
                .map(WeighbridgeService::toView).toList();
        List<DisputeView> disputes = listDisputes(orderId);
        FreezeView freeze = repo.findFreezeByOrderId(orderId)
                .map(WeighbridgeService::toView).orElse(null);
        List<ErrataView> erratas = repo.findFreezeByOrderId(orderId)
                .map(f -> repo.listErratas(f.id()).stream()
                        .map(WeighbridgeService::toView).toList())
                .orElse(List.of());
        return new OrderOverview(toView(order), weighings, disputes, freeze, erratas);
    }

    /** 查询运输单全部称量记录。 */
    @Transactional(readOnly = true)
    public List<WeighingView> listWeighings(long orderId) {
        repo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        return repo.listWeighings(orderId).stream().map(WeighbridgeService::toView).toList();
    }

    /** 查询运输单全部争议及裁决留痕。 */
    @Transactional(readOnly = true)
    public List<DisputeView> listDisputes(long orderId) {
        repo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("运输单不存在: " + orderId));
        return repo.listDisputes(orderId).stream()
                .map(d -> toView(d, repo.findAdjudicationByDisputeId(d.id())
                        .map(WeighbridgeService::toView).orElse(null)))
                .toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static void requireSameParams(String storedHash, String incomingHash) {
        if (!storedHash.equals(incomingHash)) {
            throw ApiException.conflict("requestId 已使用且参数不一致");
        }
    }

    private static String hash(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OrderView toView(OrderRow r) {
        return new OrderView(r.id(), r.orderNo(), r.vehicleNo(), r.createdAt());
    }

    private static WeighingView toView(WeighingRow r) {
        return new WeighingView(r.id(), r.transportOrderId(), r.disputeId(), r.phase().name(),
                r.weightKg(), r.weighedAt(), r.requestId());
    }

    private static DisputeView toView(DisputeRow r, AdjudicationView adjudication) {
        return new DisputeView(r.id(), r.transportOrderId(), r.reason(), r.status().name(),
                r.createdAt(), adjudication);
    }

    private static AdjudicationView toView(AdjudicationRow r) {
        return new AdjudicationView(r.id(), r.disputeId(), r.adoptedWeighingId(), r.reason(),
                r.decidedBy(), r.decidedAt());
    }

    private static FreezeView toView(FreezeRow r) {
        return new FreezeView(r.id(), r.transportOrderId(), r.tareWeightKg(),
                r.adoptedWeighingId(), r.grossWeightKg(), r.netWeightKg(), r.weighingVersion(),
                r.frozenAt());
    }

    private static ErrataView toView(ErrataRow r) {
        return new ErrataView(r.id(), r.freezeId(), r.weightKg(), r.weighedAt(), r.reason(),
                r.status(), r.createdAt());
    }
}