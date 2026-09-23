package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.ChainResponse;
import com.example.starter.consent.dto.DelegationRequest;
import com.example.starter.consent.dto.DelegationResponse;
import com.example.starter.consent.dto.DelegationRevokeRequest;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 本地数据授权 API：限时授权、委托链、记录写入、撤回与只读查询。
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

    @PostMapping("/consents/delegations")
    public DelegationResponse delegate(@Valid @RequestBody DelegationRequest request) {
        return delegationService.delegate(request);
    }

    @PostMapping("/consents/delegations/revocations")
    public DelegationResponse revokeDelegation(@Valid @RequestBody DelegationRevokeRequest request) {
        return delegationService.revoke(request);
    }

    /**
     * 当前有效委托链只读查询：返回主体到处理方的最短有效路径。
     */
    @GetMapping("/consents/delegations/chain")
    public ChainResponse chain(@RequestParam @NotBlank String subjectKey,
                               @RequestParam @NotNull Purpose purpose,
                               @RequestParam @NotNull @Min(1) Integer epoch,
                               @RequestParam @NotBlank String processorKey) {
        return delegationService.getChain(subjectKey, purpose, epoch, processorKey);
    }

    @PostMapping("/records")
    public RecordResponse write(@Valid @RequestBody RecordWriteRequest request) {
        return consentService.write(request);
    }

    /**
     * 记录只读查询：返回记录内容及不可变写入依据（授权代次、完整委托链版本与评估时刻）。
     */
    @GetMapping("/records")
    public RecordResponse read(@RequestParam @NotBlank String subjectKey,
                               @RequestParam Purpose purpose,
                               @RequestParam @NotBlank String recordKey) {
        return consentService.read(subjectKey, purpose, recordKey);
    }
}
