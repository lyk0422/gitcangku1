package com.example.starter.web;

import com.example.starter.domain.WeighTicket;
import com.example.starter.service.DisputeService;
import com.example.starter.service.OpResult;
import com.example.starter.service.QueryService;
import com.example.starter.service.SettlementService;
import com.example.starter.service.TicketService;
import com.example.starter.web.dto.Requests.CreateTicketRequest;
import com.example.starter.web.dto.Requests.ErrataSubmitRequest;
import com.example.starter.web.dto.Requests.FreezeRequest;
import com.example.starter.web.dto.Requests.GrossRequest;
import com.example.starter.web.dto.Requests.ReweighSubmitRequest;
import com.example.starter.web.dto.Requests.TareRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 称量单业务入口。 */
@RestController
@RequestMapping("/api/weigh-tickets")
public class WeighTicketController {

    private final TicketService ticketService;
    private final DisputeService disputeService;
    private final SettlementService settlementService;
    private final QueryService queryService;

    public WeighTicketController(TicketService ticketService, DisputeService disputeService,
                                 SettlementService settlementService, QueryService queryService) {
        this.ticketService = ticketService;
        this.disputeService = disputeService;
        this.settlementService = settlementService;
        this.queryService = queryService;
    }

    private static <T> ResponseEntity<T> respond(OpResult<T> result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.body());
    }

    @PostMapping
    public ResponseEntity<WeighTicket> create(@Valid @RequestBody CreateTicketRequest req) {
        return respond(ticketService.createTicket(req));
    }

    @PostMapping("/{id}/tare")
    public ResponseEntity<WeighTicket> tare(@PathVariable long id, @Valid @RequestBody TareRequest req) {
        return respond(ticketService.recordTare(id, req));
    }

    @PostMapping("/{id}/gross")
    public ResponseEntity<WeighTicket> gross(@PathVariable long id, @Valid @RequestBody GrossRequest req) {
        return respond(ticketService.recordGross(id, req));
    }

    @PostMapping("/{id}/reweighs")
    public ResponseEntity<?> reweigh(@PathVariable long id, @Valid @RequestBody ReweighSubmitRequest req) {
        return respond(disputeService.submitReweigh(id, req));
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<?> freeze(@PathVariable long id, @Valid @RequestBody FreezeRequest req) {
        return respond(settlementService.freeze(id, req));
    }

    @PostMapping("/{id}/errata")
    public ResponseEntity<?> errata(@PathVariable long id, @Valid @RequestBody ErrataSubmitRequest req) {
        return respond(settlementService.submitErrata(id, req));
    }

    @GetMapping("/{id}")
    public QueryService.TicketDetail detail(@PathVariable long id) {
        return queryService.detail(id);
    }

    @GetMapping
    public List<WeighTicket> list(@RequestParam String vehicleNo) {
        return queryService.listByVehicle(vehicleNo);
    }
}