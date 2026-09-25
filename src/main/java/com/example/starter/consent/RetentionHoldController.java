package com.example.starter.consent;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.EpochHoldStatusResponse;
import com.example.starter.consent.dto.HoldCreateRequest;
import com.example.starter.consent.dto.HoldReleaseHistoryItem;
import com.example.starter.consent.dto.HoldReleaseRequest;
import com.example.starter.consent.dto.HoldResponse;
import com.example.starter.consent.dto.LegalHoldRecordResponse;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.PurgeResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保留冻结 API：冻结创建/解除、保留角色只读查询、撤回后清除与 epoch 冻结状态查询。
 *
 * <p>保留角色操作须带头 X-Actor-Id 与 X-Actor-Role: RETENTION_OFFICER。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RetentionHoldController {

    private final RetentionHoldService retentionHoldService;

    public RetentionHoldController(RetentionHoldService retentionHoldService) {
        this.retentionHoldService = retentionHoldService;
    }

    @PostMapping("/retention/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse createHold(@Valid @RequestBody HoldCreateRequest request,
                                   @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
                                   @RequestHeader(value = "X-Actor-Role", required = false) String actorRole) {
        return retentionHoldService.createHold(request, actor(actorId, actorRole));
    }

    @PostMapping("/retention/holds/{holdKey}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldReleaseHistoryItem releaseHold(@PathVariable @NotBlank String holdKey,
                                              @Valid @RequestBody HoldReleaseRequest request,
                                              @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
                                              @RequestHeader(value = "X-Actor-Role", required = false) String actorRole) {
        return retentionHoldService.releaseHold(holdKey, request, actor(actorId, actorRole));
    }

    @GetMapping("/retention/records")
    public LegalHoldRecordResponse legalHoldRead(@RequestParam @NotBlank String subjectKey,
                                                 @RequestParam @NotNull Purpose purpose,
                                                 @RequestParam @NotNull @Min(1) Integer epoch,
                                                 @RequestParam @NotBlank String recordKey,
                                                 @RequestHeader(value = "X-Actor-Id", required = false) String actorId,
                                                 @RequestHeader(value = "X-Actor-Role", required = false) String actorRole) {
        return retentionHoldService.legalHoldRead(subjectKey, purpose, epoch, recordKey, actor(actorId, actorRole));
    }

    @PostMapping("/consents/purges")
    public PurgeResponse purge(@Valid @RequestBody PurgeRequest request) {
        return retentionHoldService.purge(request);
    }

    @GetMapping("/retention/epochs/status")
    public EpochHoldStatusResponse epochStatus(@RequestParam @NotBlank String subjectKey,
                                               @RequestParam @NotNull Purpose purpose,
                                               @RequestParam @NotNull @Min(1) Integer epoch) {
        return retentionHoldService.epochStatus(subjectKey, purpose, epoch);
    }

    private Actor actor(String actorId, String actorRole) {
        return new Actor(actorId, Actor.ROLE_RETENTION_OFFICER.equals(actorRole));
    }
}
