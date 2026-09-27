package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.ErasureRepository.ErasureRow;
import com.example.starter.consent.MigrationRepository.MigrationItemRow;
import com.example.starter.consent.MigrationRepository.MigrationRow;
import com.example.starter.consent.RegionRepository.RegionRow;
import com.example.starter.consent.dto.EraseRequest;
import com.example.starter.consent.dto.EraseResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.LegalHoldRequest;
import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.MigrationResponse;
import com.example.starter.consent.dto.QueryRequest;
import com.example.starter.consent.dto.QueryResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RevokeRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 本地数据授权 API：区域登记、授权（epoch＋驻留区域）、同区域写入、撤回、
 * 跨区域迁移、数据清除、跨对象合规快照查询、合法保留，以及只读明细/历史/诊断查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    // ---------- 驻留区域 ----------

    @PostMapping("/residency/regions")
    public RegionRegisterRequest.Response registerRegion(@Valid @RequestBody RegionRegisterRequest request) {
        return consentService.registerRegion(request);
    }

    @GetMapping("/residency/regions")
    public List<RegionRow> listRegions() {
        return consentService.listRegions();
    }

    // ---------- 授权与撤回 ----------

    @PostMapping("/consents/grants")
    public GrantResponse grant(@Valid @RequestBody GrantRequest request) {
        return consentService.grant(request);
    }

    @PostMapping("/consents/revocations")
    public GrantResponse revoke(@Valid @RequestBody RevokeRequest request) {
        return consentService.revoke(request);
    }

    @GetMapping("/consents/grants")
    public List<GrantRow> grantHistory(@RequestParam @NotBlank String subjectKey,
                                       @RequestParam Purpose purpose) {
        return consentService.listGrantHistory(subjectKey, purpose);
    }

    // ---------- 记录写入、单对象读取与历史 ----------

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

    @GetMapping("/records/history")
    public List<RecordRow> recordHistory(@RequestParam @NotBlank String subjectKey,
                                         @RequestParam Purpose purpose) {
        return consentService.listRecordHistory(subjectKey, purpose);
    }

    // ---------- 数据清除 ----------

    @PostMapping("/records/erasures")
    @ResponseStatus(HttpStatus.OK)
    public EraseResponse erase(@Valid @RequestBody EraseRequest request) {
        return consentService.erase(request);
    }

    @GetMapping("/records/erasures")
    public List<ErasureRow> erasures(@RequestParam @NotBlank String subjectKey,
                                     @RequestParam Purpose purpose) {
        return consentService.listErasures(subjectKey, purpose);
    }

    // ---------- 跨区域迁移 ----------

    @PostMapping("/residency/migrations")
    public MigrationResponse migrate(@Valid @RequestBody MigrationRequest request) {
        return consentService.migrate(request);
    }

    @GetMapping("/residency/migrations")
    public List<MigrationRow> migrations(@RequestParam @NotBlank String subjectKey,
                                         @RequestParam Purpose purpose) {
        return consentService.listMigrations(subjectKey, purpose);
    }

    @GetMapping("/residency/migrations/{migrationId}/items")
    public List<MigrationItemRow> migrationItems(@PathVariable String migrationId) {
        return consentService.listMigrationItems(migrationId);
    }

    // ---------- 跨对象合规快照查询 ----------

    @PostMapping("/records/query")
    public QueryResponse queryRecords(@Valid @RequestBody QueryRequest request) {
        return consentService.queryRecords(request);
    }

    @GetMapping("/query-snapshots/{snapshotId}")
    public QueryResponse getSnapshot(@PathVariable String snapshotId) {
        return consentService.getSnapshot(snapshotId);
    }

    // ---------- 合法保留 ----------

    @PostMapping("/legal-holds")
    @ResponseStatus(HttpStatus.CREATED)
    public com.example.starter.consent.dto.LegalHoldResponse registerLegalHold(
            @Valid @RequestBody LegalHoldRequest request) {
        return consentService.registerLegalHold(request);
    }

    @GetMapping("/legal-holds")
    public List<com.example.starter.consent.dto.LegalHoldResponse> legalHolds(
            @RequestParam @NotBlank String subjectKey,
            @RequestParam Purpose purpose,
            @RequestParam int epoch,
            @RequestParam @NotBlank String recordKey) {
        return consentService.listLegalHolds(subjectKey, purpose, epoch, recordKey);
    }
}
