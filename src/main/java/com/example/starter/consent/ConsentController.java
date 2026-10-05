package com.example.starter.consent;

import java.util.LinkedHashSet;
import java.util.Set;

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
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RestrictionReplaceRequest;
import com.example.starter.consent.dto.RestrictionResponse;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.dto.StructuredRecordReadResponse;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;
import com.example.starter.consent.dto.StructuredRecordWriteResponse;

import jakarta.validation.Valid;
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

    @PostMapping("/structured-records")
    public StructuredRecordWriteResponse writeStructured(@Valid @RequestBody StructuredRecordWriteRequest request) {
        return consentService.writeStructured(request);
    }

    /**
     * 结构化读取：可选 fields 参数（逗号分隔）指定字段集合；未知字段返回 400，禁止字段不出现。
     */
    @GetMapping("/structured-records")
    public StructuredRecordReadResponse readStructured(@RequestParam @NotBlank String subjectKey,
                                                       @RequestParam Purpose purpose,
                                                       @RequestParam @NotBlank String recordKey,
                                                       @RequestParam(required = false) String fields) {
        return consentService.readStructured(subjectKey, purpose, recordKey, parseFieldsParam(fields));
    }

    @PostMapping("/consents/restrictions")
    public RestrictionResponse replaceRestriction(@Valid @RequestBody RestrictionReplaceRequest request) {
        return consentService.replaceRestriction(request);
    }

    private static Set<String> parseFieldsParam(String fields) {
        if (fields == null || fields.isBlank()) {
            return null;
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String name : fields.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
        }
        return requested.isEmpty() ? null : requested;
    }
}
