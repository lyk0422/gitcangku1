package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.AttestationRevokeRequest;
import com.example.starter.consent.dto.ReadAuditResponse;
import com.example.starter.consent.dto.RecordResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 接收方承诺与读取 API：承诺提交/撤回/状态查询、带承诺校验的读取，以及授权方的读取审计查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RecipientController {

    private final AttestationService attestationService;

    public RecipientController(AttestationService attestationService) {
        this.attestationService = attestationService;
    }

    @PostMapping("/recipient/attestations")
    public AttestationResponse attest(@Valid @RequestBody AttestationRequest request) {
        return attestationService.attest(request);
    }

    @PostMapping("/recipient/attestations/revocations")
    public AttestationResponse revokeAttestation(@Valid @RequestBody AttestationRevokeRequest request) {
        return attestationService.revokeAttestation(request);
    }

    @GetMapping("/recipient/attestations")
    public List<AttestationResponse> listAttestations(@RequestParam @NotBlank String recipientKey) {
        return attestationService.listAttestations(recipientKey);
    }

    @GetMapping("/recipient/records")
    public RecordResponse read(@RequestParam @NotBlank String recipientKey,
                               @RequestParam @NotBlank String subjectKey,
                               @RequestParam Purpose purpose,
                               @RequestParam @NotBlank String recordKey) {
        return attestationService.read(recipientKey, subjectKey, purpose, recordKey);
    }

    @GetMapping("/consents/read-audits")
    public List<ReadAuditResponse> listAudits(@RequestParam @NotBlank String subjectKey) {
        return attestationService.listAudits(subjectKey);
    }
}
