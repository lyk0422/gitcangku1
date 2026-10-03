package com.example.starter.service;

import com.example.starter.domain.Dispute;
import com.example.starter.domain.ErrataRequest;
import com.example.starter.domain.ReweighRequest;
import com.example.starter.domain.SettlementFreeze;
import com.example.starter.domain.WeighTicket;
import com.example.starter.error.BizException;
import com.example.starter.repo.DisputeRepository;
import com.example.starter.repo.ErrataRepository;
import com.example.starter.repo.FreezeRepository;
import com.example.starter.repo.ReweighRepository;
import com.example.starter.repo.WeighTicketRepository;
import com.example.starter.web.dto.Requests.ErrataSubmitRequest;
import com.example.starter.web.dto.Requests.FreezeRequest;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 净重冻结与勘误。 */
@Service
public class SettlementService {

    private final WeighTicketRepository tickets;
    private final ReweighRepository reweighs;
    private final DisputeRepository disputes;
    private final FreezeRepository freezes;
    private final ErrataRepository errata;
    private final Clock clock;

    public SettlementService(WeighTicketRepository tickets, ReweighRepository reweighs,
                             DisputeRepository disputes, FreezeRepository freezes,
                             ErrataRepository errata, Clock clock) {
        this.tickets = tickets;
        this.reweighs = reweighs;
        this.disputes = disputes;
        this.freezes = freezes;
        this.errata = errata;
        this.clock = clock;
    }

    /**
     * 冻结最终净重：净重 = 采纳毛重 - 皮重。
     * 未出场 422；存在未裁决争议 409；请求方提交的复称版本集合与已提交集合不一致 409；
     * 并发冻结由 ticket_id 唯一约束保证只形成一份快照。
     */
    @Transactional
    public OpResult<SettlementFreeze> freeze(long ticketId, FreezeRequest req) {
        WeighTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        List<ReweighRequest> submitted = reweighs.findByTicketId(ticketId);
        String versionSet = submitted.stream().map(r -> String.valueOf(r.id())).collect(Collectors.joining(","));
        String expected = req.expectedReweighIds().stream().sorted()
                .map(String::valueOf).collect(Collectors.joining(","));
        String hash = Hashing.of(expected);
        var existingFreeze = freezes.findByTicketId(ticketId);
        if (existingFreeze.isPresent()) {
            SettlementFreeze f = existingFreeze.get();
            if (req.requestId().equals(f.requestId()) && hash.equals(f.requestHash())) {
                return OpResult.replayed(f);
            }
            throw BizException.conflict("ALREADY_FROZEN", "净重已冻结，快照不可覆盖");
        }
        if (t.grossKg() == null) {
            throw BizException.unprocessable("NO_EXIT_WEIGHING", "缺少出场称量，不能冻结净重");
        }
        var dispute = disputes.findByTicketId(ticketId);
        if (dispute.isPresent() && Dispute.STATUS_OPEN.equals(dispute.get().status())) {
            throw BizException.conflict("UNRESOLVED_DISPUTE", "存在未裁决争议，不能结算冻结");
        }
        String actual = submitted.stream().map(ReweighRequest::id).sorted()
                .map(String::valueOf).collect(Collectors.joining(","));
        if (!expected.equals(actual)) {
            throw BizException.conflict("VERSION_SET_MISMATCH",
                    "复称版本集合不一致：期望[" + expected + "]，实际[" + actual + "]");
        }
        int adoptedGross = t.grossKg();
        String source = Dispute.SOURCE_ORIGINAL;
        Long adoptedReweighId = null;
        if (dispute.isPresent() && Dispute.SOURCE_REWEIGH.equals(dispute.get().adoptedSource())) {
            ReweighRequest adopted = reweighs.findById(dispute.get().adoptedReweighId()).orElseThrow();
            adoptedGross = adopted.grossKg();
            source = Dispute.SOURCE_REWEIGH;
            adoptedReweighId = adopted.id();
        }
        int net = adoptedGross - t.tareKg();
        if (net < 0) {
            throw BizException.unprocessable("NEGATIVE_NET_WEIGHT", "采纳毛重小于皮重，净重为负");
        }
        int marked = tickets.updateStatusIfNot(ticketId, WeighTicket.STATUS_FROZEN, WeighTicket.STATUS_FROZEN);
        if (marked == 0) {
            throw BizException.conflict("ALREADY_FROZEN", "净重已冻结，快照不可覆盖");
        }
        SettlementFreeze freeze = new SettlementFreeze(0, ticketId, t.tareKg(), adoptedGross, net,
                source, adoptedReweighId, versionSet, req.requestId(), hash, clock.instant());
        try {
            long id = freezes.insert(freeze);
            return OpResult.created(freezes.findByTicketId(ticketId).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("ALREADY_FROZEN", "净重已冻结，快照不可覆盖");
        }
    }

    /** 冻结后的新称量仅形成勘误申请，不覆盖既有结算快照。 */
    @Transactional
    public OpResult<ErrataRequest> submitErrata(long ticketId, ErrataSubmitRequest req) {
        WeighTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        String hash = Hashing.of(String.valueOf(req.newGrossKg()), req.reason());
        var existing = errata.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            ErrataRequest e = existing.get();
            if (hash.equals(e.requestHash())) {
                return OpResult.replayed(e);
            }
            throw BizException.conflict("IDEMPOTENT_CONFLICT", "相同 requestId 但参数不同");
        }
        if (!WeighTicket.STATUS_FROZEN.equals(t.status())) {
            throw BizException.unprocessable("NOT_FROZEN", "净重未冻结，应走正常称量或复称流程");
        }
        long id = errata.insert(new ErrataRequest(0, ticketId, req.newGrossKg(), req.reason(),
                ErrataRequest.STATUS_SUBMITTED, req.requestId(), hash, clock.instant()));
        return OpResult.created(errata.findByRequestId(req.requestId()).orElseThrow());
    }
}