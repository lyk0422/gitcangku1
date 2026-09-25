package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.CreateHoldRequest;
import com.example.starter.consent.dto.HoldResponse;
import com.example.starter.consent.dto.HoldStatusEntry;
import com.example.starter.consent.dto.LegalHoldRecordResponse;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.PurgeResponse;
import com.example.starter.consent.dto.ReleaseHistoryEntry;
import com.example.starter.consent.dto.ReleaseHoldRequest;
import com.example.starter.consent.dto.ReleaseResponse;
import com.example.starter.consent.dto.RetainedCountResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保留冻结 API：冻结创建、人工解除、保留权限只读查询、清除与状态查询。
 * 保留权限操作通过请求头 X-Retention-Role: RETENTION 声明保留角色。
 */
@Validated
@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @PostMapping("/holds")
    public HoldResponse createHold(@Valid @RequestBody CreateHoldRequest request) {
        return retentionService.createHold(request);
    }

    @PostMapping("/holds/releases")
    public ReleaseResponse release(@Valid @RequestBody ReleaseHoldRequest request,
                                   @RequestHeader(value = RetentionService.RETENTION_ROLE_HEADER,
                                           required = false) String retentionRole) {
        return retentionService.release(request, retentionRole);
    }

    @PostMapping("/purges")
    public PurgeResponse purge(@Valid @RequestBody PurgeRequest request) {
        return retentionService.purge(request);
    }

    @GetMapping("/records")
    public LegalHoldRecordResponse readRecordUnderHold(@RequestParam @NotBlank String subjectKey,
                                                       @RequestParam @NotNull Purpose purpose,
                                                       @RequestParam @Min(1) int epoch,
                                                       @RequestParam @NotBlank String recordKey,
                                                       @RequestHeader(value = RetentionService.RETENTION_ROLE_HEADER,
                                                               required = false) String retentionRole) {
        return retentionService.readRecordUnderHold(subjectKey, purpose, epoch, recordKey, retentionRole);
    }

    @GetMapping("/holds")
    public List<HoldStatusEntry> listHolds(@RequestParam @NotBlank String subjectKey,
                                           @RequestParam @NotNull Purpose purpose,
                                           @RequestParam @Min(1) int epoch) {
        return retentionService.listHolds(subjectKey, purpose, epoch);
    }

    @GetMapping("/retained-count")
    public RetainedCountResponse retainedCount(@RequestParam @NotBlank String subjectKey,
                                               @RequestParam @NotNull Purpose purpose,
                                               @RequestParam @Min(1) int epoch) {
        return retentionService.retainedCount(subjectKey, purpose, epoch);
    }

    @GetMapping("/releases")
    public List<ReleaseHistoryEntry> releaseHistory(@RequestParam @NotBlank String subjectKey,
                                                    @RequestParam @NotNull Purpose purpose,
                                                    @RequestParam @Min(1) int epoch) {
        return retentionService.releaseHistory(subjectKey, purpose, epoch);
    }
}
