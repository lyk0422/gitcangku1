package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CampaignService;
import com.example.starter.firmware.service.MigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 投放活动：活动与队列管理、设备入组、回执入账、人工恢复、活动结束与队列迁移单。
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;
    private final MigrationService migrationService;

    public CampaignController(CampaignService campaignService, MigrationService migrationService) {
        this.campaignService = campaignService;
        this.migrationService = migrationService;
    }

    @PostMapping
    public CampaignView create(@Valid @RequestBody CreateCampaignRequest request) {
        return campaignService.create(request);
    }

    @PostMapping("/{campaignId}/end")
    public CampaignView end(@PathVariable long campaignId, @Valid @RequestBody RequestIdBody request) {
        return campaignService.end(campaignId, request.requestId());
    }

    @PostMapping("/{campaignId}/cohorts")
    public CohortView createCohort(@PathVariable long campaignId,
                                   @Valid @RequestBody CreateCohortRequest request) {
        return campaignService.createCohort(campaignId, request);
    }

    @GetMapping("/{campaignId}/cohorts")
    public List<CohortView> cohorts(@PathVariable long campaignId) {
        return campaignService.listCohorts(campaignId);
    }

    @PostMapping("/{campaignId}/cohorts/{cohortId}/resume")
    public CohortView resumeCohort(@PathVariable long campaignId, @PathVariable long cohortId,
                                   @Valid @RequestBody RequestIdBody request) {
        return campaignService.resumeCohort(campaignId, cohortId, request.requestId());
    }

    @PostMapping("/{campaignId}/assignments")
    public AssignmentView enroll(@PathVariable long campaignId,
                                 @Valid @RequestBody EnrollDeviceRequest request) {
        return campaignService.enroll(campaignId, request);
    }

    @GetMapping("/{campaignId}/assignments/{deviceId}")
    public AssignmentView assignment(@PathVariable long campaignId, @PathVariable String deviceId) {
        return campaignService.getAssignment(campaignId, deviceId);
    }

    @PostMapping("/{campaignId}/receipts")
    public CampaignReceiptView receipt(@PathVariable long campaignId,
                                       @Valid @RequestBody CampaignReceiptRequest request) {
        return campaignService.receipt(campaignId, request);
    }

    @PostMapping("/{campaignId}/migrations/preview")
    public MigrationPreviewView preview(@PathVariable long campaignId,
                                        @Valid @RequestBody PreviewMigrationRequest request) {
        return migrationService.preview(campaignId, request.items());
    }

    @PostMapping("/{campaignId}/migrations")
    public MigrationView activate(@PathVariable long campaignId,
                                  @Valid @RequestBody ActivateMigrationRequest request) {
        return migrationService.activate(campaignId, request);
    }

    @GetMapping("/{campaignId}/migrations/{migrationKey}")
    public MigrationView migration(@PathVariable long campaignId, @PathVariable String migrationKey) {
        return migrationService.getByKey(campaignId, migrationKey);
    }
}
