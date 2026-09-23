package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CohortService;
import com.example.starter.firmware.service.MigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 队列迁移：预览（不写数据）、激活（整单原子提交）与只读查询（迁移前后队列、指令代次、迟到回执证据）。
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final MigrationService migrationService;
    private final CohortService cohortService;

    public MigrationController(MigrationService migrationService, CohortService cohortService) {
        this.migrationService = migrationService;
        this.cohortService = cohortService;
    }

    @PostMapping("/preview")
    public MigrationPreviewResponse preview(@Valid @RequestBody MigrationItemInput.Preview request) {
        return migrationService.preview(request);
    }

    @PostMapping("/activate")
    public MigrationActivateResponse activate(@Valid @RequestBody MigrationItemInput.Activate request) {
        return migrationService.activate(request);
    }

    @GetMapping("/{migrationId}")
    public MigrationDetailResponse detail(@PathVariable long migrationId) {
        return migrationService.get(migrationId);
    }

    @GetMapping("/cohorts/{cohortId}")
    public CohortView cohort(@PathVariable long cohortId) {
        return cohortService.getCohort(cohortId);
    }
}
