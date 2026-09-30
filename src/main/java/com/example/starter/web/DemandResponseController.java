package com.example.starter.web;

import com.example.starter.service.DemandResponseService;
import com.example.starter.web.dto.AccountView;
import com.example.starter.web.dto.CommitmentRequest;
import com.example.starter.web.dto.CommitmentView;
import com.example.starter.web.dto.InvitationRequest;
import com.example.starter.web.dto.InvitationView;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingVersionView;
import com.example.starter.web.dto.SettlementRequest;
import com.example.starter.web.dto.SettlementView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 削峰需求响应接口：邀约 → 承诺 → 计量确认/更正 → 结算，及单用户账目查询。
 */
@RestController
@RequestMapping("/api/invitations")
public class DemandResponseController {

    private final DemandResponseService service;

    public DemandResponseController(DemandResponseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView createInvitation(@Valid @RequestBody InvitationRequest request) {
        return service.createInvitation(request);
    }

    @PostMapping("/{invitationId}/commitments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommitmentView createCommitment(@PathVariable long invitationId,
                                           @Valid @RequestBody CommitmentRequest request) {
        return service.createCommitment(invitationId, request);
    }

    @PostMapping("/{invitationId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingVersionView submitReading(@PathVariable long invitationId,
                                            @Valid @RequestBody ReadingRequest request) {
        return service.submitReading(invitationId, request);
    }

    @PostMapping("/{invitationId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementView settle(@PathVariable long invitationId,
                                 @Valid @RequestBody SettlementRequest request) {
        return service.settle(invitationId, request);
    }

    @GetMapping("/{invitationId}/accounts/{userId}")
    public AccountView getAccount(@PathVariable long invitationId,
                                  @PathVariable String userId) {
        return service.getAccount(invitationId, userId);
    }
}
