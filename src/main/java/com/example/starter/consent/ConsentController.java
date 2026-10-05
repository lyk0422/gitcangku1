package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordMetadataResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RectifyRequest;
import com.example.starter.consent.dto.RevokeRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 本地数据授权 API：授权、记录写入、撤回与查询。
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

    @PostMapping("/records/rectifications")
    public RecordResponse rectify(@Valid @RequestBody RectifyRequest request) {
        return consentService.rectify(request);
    }

    @GetMapping("/records/history")
    public RecordResponse readHistory(@RequestParam @NotBlank String subjectKey,
                                      @RequestParam Purpose purpose,
                                      @RequestParam @Min(1) int epoch,
                                      @RequestParam @NotBlank String recordKey,
                                      @RequestParam @Min(1) int version) {
        return consentService.readHistory(subjectKey, purpose, epoch, recordKey, version);
    }

    @GetMapping("/records/metadata")
    public RecordMetadataResponse metadata(@RequestParam @NotBlank String subjectKey,
                                           @RequestParam Purpose purpose,
                                           @RequestParam @NotBlank String recordKey) {
        return consentService.metadata(subjectKey, purpose, recordKey);
    }
}
