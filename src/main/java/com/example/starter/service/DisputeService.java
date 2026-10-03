package com.example.starter.service;

import com.example.starter.domain.Dispute;
import com.example.starter.domain.ReweighRequest;
import com.example.starter.domain.WeighTicket;
import com.example.starter.error.BizException;
import com.example.starter.repo.DisputeRepository;
import com.example.starter.repo.ReweighRepository;
import com.example.starter.repo.WeighTicketRepository;
import com.example.starter.web.dto.Requests.AdjudicateRequest;
import com.example.starter.web.dto.Requests.ReweighSubmitRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 复称申请与争议裁决。 */
@Service
public class DisputeService {

    /** 出场后允许申请复称的窗口。 */
    static final Duration REWEIGH_WINDOW = Duration.ofMinutes(30);

    private final WeighTicketRepository tickets;
    private final ReweighRepository reweighs;
    private final DisputeRepository disputes;
    private final Clock clock;

    public DisputeService(WeighTicketRepository tickets, ReweighRepository reweighs,
                          DisputeRepository disputes, Clock clock) {
        this.tickets = tickets;
        this.reweighs = reweighs;
        this.disputes = disputes;
        this.clock = clock;
    }

    /**
     * 提交复称申请：出场后 30 分钟内可追加新毛重记录；首次申请自动开立争议。
     * 与净重冻结互斥：对已冻结称量单返回 409。
     */
    @Transactional
    public OpResult<ReweighRequest> submitReweigh(long ticketId, ReweighSubmitRequest req) {
        WeighTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        String hash = Hashing.of(String.valueOf(req.grossKg()), req.reason());
        var existing = reweighs.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            ReweighRequest r = existing.get();
            if (hash.equals(r.requestHash())) {
                return OpResult.replayed(r);
            }
            throw BizException.conflict("IDEMPOTENT_CONFLICT", "相同 requestId 但参数不同");
        }
        if (WeighTicket.STATUS_FROZEN.equals(t.status())) {
            throw BizException.conflict("TICKET_FROZEN", "净重已冻结，新称量只能形成勘误申请");
        }
        if (t.grossKg() == null) {
            throw BizException.unprocessable("NO_EXIT_WEIGHING", "缺少出场称量，不能申请复称");
        }
        if (req.grossKg() < t.tareKg()) {
            throw BizException.unprocessable("NEGATIVE_NET_WEIGHT", "复称毛重小于皮重，净重为负");
        }
        Duration sinceGross = Duration.between(t.grossTimeUtc(), clock.instant());
        if (sinceGross.compareTo(REWEIGH_WINDOW) > 0) {
            throw BizException.unprocessable("REWEIGH_WINDOW_EXPIRED", "出场超过30分钟，复称申请逾期");
        }
        var dispute = disputes.findByTicketId(ticketId);
        if (dispute.isPresent() && Dispute.STATUS_RESOLVED.equals(dispute.get().status())) {
            throw BizException.conflict("DISPUTE_ALREADY_RESOLVED", "争议已裁决，不能再追加复称");
        }
        if (dispute.isEmpty()) {
            disputes.insert(new Dispute(0, ticketId, Dispute.STATUS_OPEN, null, null, null, null, null,
                    null, null, clock.instant()));
        }
        long id = reweighs.insert(new ReweighRequest(0, ticketId, req.grossKg(), req.reason(),
                ReweighRequest.STATUS_SUBMITTED, req.requestId(), hash, clock.instant()));
        return OpResult.created(reweighs.findById(id).orElseThrow());
    }

    /**
     * 裁决争议：裁决员从原称量或复称中明确选一条，理由留痕。
     * 并发裁决只有一个生效，其余 409；同 requestId 同参重放返回原裁决。
     */
    @Transactional
    public OpResult<Dispute> adjudicate(long disputeId, AdjudicateRequest req) {
        Dispute d = disputes.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("DISPUTE_NOT_FOUND", "争议不存在: " + disputeId));
        String hash = Hashing.of(req.adoptedSource(), String.valueOf(req.reweighId()), req.reason(), req.adjudicator());
        if (Dispute.STATUS_RESOLVED.equals(d.status())) {
            if (req.requestId().equals(d.requestId()) && hash.equals(d.requestHash())) {
                return OpResult.replayed(d);
            }
            throw BizException.conflict("DISPUTE_ALREADY_RESOLVED", "争议已裁决，最终净重唯一");
        }
        Long adoptedReweighId = null;
        if (Dispute.SOURCE_REWEIGH.equals(req.adoptedSource())) {
            if (req.reweighId() == null) {
                throw BizException.unprocessable("REWEIGH_REQUIRED", "采纳复称时必须指定复称申请ID");
            }
            ReweighRequest r = reweighs.findById(req.reweighId())
                    .orElseThrow(() -> BizException.unprocessable("REWEIGH_NOT_FOUND", "复称申请不存在"));
            if (r.ticketId() != d.ticketId() || !ReweighRequest.STATUS_SUBMITTED.equals(r.status())) {
                throw BizException.unprocessable("REWEIGH_NOT_ADOPTABLE", "复称申请不属于该争议或已被处理");
            }
            adoptedReweighId = r.id();
        } else if (!Dispute.SOURCE_ORIGINAL.equals(req.adoptedSource())) {
            throw BizException.unprocessable("INVALID_ADOPTED_SOURCE", "采纳来源只能是 ORIGINAL 或 REWEIGH");
        }
        int updated = disputes.resolveIfOpen(disputeId, req.adoptedSource(), adoptedReweighId,
                req.reason(), req.adjudicator(), clock.instant(), req.requestId(), hash);
        if (updated == 0) {
            throw BizException.conflict("DISPUTE_ALREADY_RESOLVED", "争议已被并发裁决");
        }
        for (ReweighRequest r : reweighs.findByTicketId(d.ticketId())) {
            if (ReweighRequest.STATUS_SUBMITTED.equals(r.status())) {
                reweighs.updateStatus(r.id(),
                        r.id() == (adoptedReweighId == null ? -1 : adoptedReweighId)
                                ? ReweighRequest.STATUS_ADOPTED : ReweighRequest.STATUS_REJECTED);
            }
        }
        return OpResult.created(disputes.findById(disputeId).orElseThrow());
    }
}