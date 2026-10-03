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
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 查询：定位全部称量、争议状态、采纳记录和净重。 */
@Service
public class QueryService {

    /** 称量单完整视图。previewNetKg 为冻结前按当前毛重试算的净重（公斤），未登记毛重时为 null。 */
    public record TicketDetail(WeighTicket ticket, Integer previewNetKg, List<ReweighRequest> reweighs,
                               Dispute dispute, SettlementFreeze freeze, List<ErrataRequest> errata) {
    }

    private final WeighTicketRepository tickets;
    private final ReweighRepository reweighs;
    private final DisputeRepository disputes;
    private final FreezeRepository freezes;
    private final ErrataRepository errata;

    public QueryService(WeighTicketRepository tickets, ReweighRepository reweighs,
                        DisputeRepository disputes, FreezeRepository freezes, ErrataRepository errata) {
        this.tickets = tickets;
        this.reweighs = reweighs;
        this.disputes = disputes;
        this.freezes = freezes;
        this.errata = errata;
    }

    @Transactional(readOnly = true)
    public TicketDetail detail(long ticketId) {
        WeighTicket t = tickets.findById(ticketId)
                .orElseThrow(() -> BizException.notFound("TICKET_NOT_FOUND", "称量单不存在: " + ticketId));
        Integer preview = t.grossKg() == null ? null : t.grossKg() - t.tareKg();
        return new TicketDetail(t, preview, reweighs.findByTicketId(ticketId),
                disputes.findByTicketId(ticketId).orElse(null),
                freezes.findByTicketId(ticketId).orElse(null),
                errata.findByTicketId(ticketId));
    }

    @Transactional(readOnly = true)
    public List<WeighTicket> listByVehicle(String vehicleNo) {
        return tickets.findByVehicleNo(vehicleNo);
    }

    @Transactional(readOnly = true)
    public Dispute dispute(long disputeId) {
        return disputes.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("DISPUTE_NOT_FOUND", "争议不存在: " + disputeId));
    }
}