package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordViewResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.dto.ScopeCreateRequest;
import com.example.starter.consent.dto.ScopeResponse;
import com.example.starter.consent.dto.ScopeRevokeRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 本地数据授权 API：授权、子范围、记录写入、撤回（整体/子范围）与查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @PostMapping("/consents/grants")
    public GrantResponse grant(@Valid @RequestBody GrantRequest request) {
        return consentService.grant(request);
    }

    @PostMapping("/consents/revocations")
    public GrantResponse revoke(@Valid @RequestBody RevokeRequest request) {
        return consentService.revoke(request);
    }

    @PostMapping("/consents/scopes")
    public ScopeResponse createScope(@Valid @RequestBody ScopeCreateRequest request) {
        return consentService.createScope(request);
    }

    @PostMapping("/consents/scope-revocations")
    public ScopeResponse revokeScope(@Valid @RequestBody ScopeRevokeRequest request) {
        return consentService.revokeScope(request);
    }

    @GetMapping("/consents/scopes")
    public List<ScopeResponse> listScopes(@RequestParam @NotBlank String subjectKey,
                                          @RequestParam @NotNull Purpose purpose,
                                          @RequestParam @NotNull @Min(1) Integer epoch) {
        return consentService.listScopes(subjectKey, purpose, epoch);
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

    @GetMapping("/records/aggregate")
    public List<RecordViewResponse> aggregate(@RequestParam @NotBlank String subjectKey,
                                              @RequestParam @NotNull Purpose purpose,
                                              @RequestParam @NotNull @Min(1) Integer epoch) {
        return consentService.listEpochRecords(subjectKey, purpose, epoch);
    }
}
