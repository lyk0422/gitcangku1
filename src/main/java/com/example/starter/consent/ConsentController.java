package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.ChainResponse;
import com.example.starter.consent.dto.DelegateRequest;
import com.example.starter.consent.dto.DelegationResponse;
import com.example.starter.consent.dto.DelegationRevokeRequest;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 本地数据授权 API：限时授权、委托链管理、记录写入、撤回与只读查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ConsentController {

    private final ConsentService consentService;
    private final DelegationService delegationService;

    public ConsentController(ConsentService consentService, DelegationService delegationService) {
        this.consentService = consentService;
        this.delegationService = delegationService;
    }

    @PostMapping("/consents/grants")
    public GrantResponse grant(@Valid @RequestBody GrantRequest request) {
        return consentService.grant(request);
    }

    @PostMapping("/consents/revocations")
    public GrantResponse revoke(@Valid @RequestBody RevokeRequest request) {
        return consentService.revoke(request);
    }

    @PostMapping("/delegations")
    public DelegationResponse delegate(@Valid @RequestBody DelegateRequest request) {
        return delegationService.delegate(request);
    }

    @PostMapping("/delegations/revocations")
    public DelegationResponse revokeDelegation(@Valid @RequestBody DelegationRevokeRequest request) {
        return delegationService.revoke(request);
    }

    @GetMapping("/delegations/chain")
    public ChainResponse currentChain(@RequestParam @NotBlank String subjectKey,
                                      @RequestParam Purpose purpose,
                                      @RequestParam(required = false) Integer epoch,
                                      @RequestParam @NotBlank String callerKey) {
        return delegationService.currentChain(subjectKey, purpose, epoch, callerKey);
    }

    @PostMapping("/records")
    public RecordResponse write(@Valid @RequestBody RecordWriteRequest request) {
        return consentService.write(request);
    }

    @GetMapping("/records")
    public RecordResponse read(@RequestParam @NotBlank String subjectKey,
                               @RequestParam Purpose purpose,
                               @RequestParam @NotBlank String recordKey) {
        return consentService.read(subjectKey, purpose, recordKey);
    }
}
