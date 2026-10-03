package com.example.starter.service;

import com.example.starter.domain.WeighTicket;
import com.example.starter.error.BizException;
import com.example.starter.repo.TransportOrderRepository;
import com.example.starter.repo.WeighTicketRepository;
import com.example.starter.web.dto.Requests.CreateTicketRequest;
import com.example.starter.web.dto.Requests.GrossRequest;
import com.example.starter.web.dto.Requests.TareRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 称量单服务：建单、入场皮重、出场毛重。 */
@Service
public class TicketService {

    private final WeighTicketRepository tickets;
    private final TransportOrderRepository orders;
    private final Clock clock;

    public TicketService(WeighTicketRepository tickets, TransportOrderRepository orders, Clock clock) {
        this.tickets = tickets;
        this.orders = orders;
        this.clock = clock;
    }

    /** 建立称量单（关联一张运输单）。同 requestId 同参重放返回原单，异参 409。 */
    @Transactional
    public OpResult<WeighTicket> createTicket(CreateTicketRequest req) {
        String hash = Hashing.of(req.transportOrderNo(), req.vehicleNo());
        var existing = tickets.findByCreateRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), hash, existing.get().createRequestHash());
        }
        if (!orders.existsByOrderNo(req.transportOrderNo())) {
            throw BizException.unprocessable("UNKNOWN_TRANSPORT_ORDER", "运输单不存在: " + req.transportOrderNo());
        }
        WeighTicket ticket = new WeighTicket(0, "WT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                req.transportOrderNo(), req.vehicleNo(), null, null, null, null,
                WeighTicket.STATUS_OPEN, req.requestId(), hash, null, null, null, null, clock.instant());
        try {
            long id = tickets.insert(ticket);
            return OpResult.created(tickets.findById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            WeighTicket dup = tickets.findByCreateRequestId(req.requestId()).orElseThrow();
            return replayOrConflict(dup, hash, dup.createRequestHash());
        }
    }

    /** 登记入场皮重。 */
    @Transactional
    public OpResult<WeighTicket> recordTare(long ticketId, TareRequest req) {
        WeighTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        String hash = Hashing.of(String.valueOf(req.tareKg()), req.tareTimeUtc().toString());
        if (t.tareKg() != null) {
            if (req.requestId().equals(t.tareRequestId()) && hash.equals(t.tareRequestHash())) {
                return OpResult.replayed(t);
            }
            throw BizException.conflict("TARE_ALREADY_RECORDED", "入场皮重已登记");
        }
        if (WeighTicket.STATUS_FROZEN.equals(t.status())) {
            throw BizException.conflict("TICKET_FROZEN", "净重已冻结，新称量只能提交勘误申请");
        }
        tickets.updateTare(ticketId, req.tareKg(), req.tareTimeUtc(), req.requestId(), hash);
        return OpResult.created(tickets.findById(ticketId).orElseThrow());
    }

    /**
     * 登记出场毛重。无入场称量、时间倒退、负净重返回 422；冻结后返回 409。
     */
    @Transactional
    public OpResult<WeighTicket> recordGross(long ticketId, GrossRequest req) {
        WeighTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        String hash = Hashing.of(String.valueOf(req.grossKg()), req.grossTimeUtc().toString());
        if (t.grossKg() != null) {
            if (req.requestId().equals(t.grossRequestId()) && hash.equals(t.grossRequestHash())) {
                return OpResult.replayed(t);
            }
            throw BizException.conflict("GROSS_ALREADY_RECORDED", "出场毛重已登记，冻结后请提交勘误申请");
        }
        if (WeighTicket.STATUS_FROZEN.equals(t.status())) {
            throw BizException.conflict("TICKET_FROZEN", "净重已冻结，新称量只能形成勘误申请");
        }
        if (t.tareKg() == null) {
            throw BizException.unprocessable("NO_ENTRY_WEIGHING", "缺少入场称量（皮重），不得生成结算重量");
        }
        if (req.grossTimeUtc().isBefore(t.tareTimeUtc())) {
            throw BizException.unprocessable("TIME_REGRESSION", "出场称量时间早于入场称量时间");
        }
        if (req.grossKg() < t.tareKg()) {
            throw BizException.unprocessable("NEGATIVE_NET_WEIGHT", "毛重小于皮重，净重为负");
        }
        tickets.updateGross(ticketId, req.grossKg(), req.grossTimeUtc(), req.requestId(), hash);
        return OpResult.created(tickets.findById(ticketId).orElseThrow());
    }

    private static OpResult<WeighTicket> replayOrConflict(WeighTicket t, String hash, String storedHash) {
        if (hash.equals(storedHash)) {
            return OpResult.replayed(t);
        }
        throw BizException.conflict("IDEMPOTENT_CONFLICT", "相同 requestId 但参数不同");
    }
}