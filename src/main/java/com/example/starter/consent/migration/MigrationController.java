package com.example.starter.consent.migration;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.migration.dto.BatchQueryRequest;
import com.example.starter.consent.migration.dto.BatchQueryResponse;
import com.example.starter.consent.migration.dto.MigrationActivateRequest;
import com.example.starter.consent.migration.dto.MigrationActivateResponse;
import com.example.starter.consent.migration.dto.MigrationEvidenceResponse;
import com.example.starter.consent.migration.dto.MigrationPreviewRequest;
import com.example.starter.consent.migration.dto.MigrationPreviewResponse;
import com.example.starter.consent.migration.dto.QueryGenerationResponse;

import jakarta.validation.Valid;

/**
 * 用途目录拆分迁移与查询代次隔离 API。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class MigrationController {

    private final PurposeMigrationService migrationService;

    public MigrationController(PurposeMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /** 迁移预览：只读，不写数据。 */
    @PostMapping("/purpose-migrations/preview")
    public MigrationPreviewResponse preview(@Valid @RequestBody MigrationPreviewRequest request) {
        return migrationService.preview(request);
    }

    /** 迁移激活：一个事务内发布新目录代次并完成授权拆分与数据改绑。 */
    @PostMapping("/purpose-migrations")
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationActivateResponse activate(@Valid @RequestBody MigrationActivateRequest request) {
        return migrationService.activate(request);
    }

    /** 迁移证据只读查询，稳定排序。 */
    @GetMapping("/purpose-migrations/evidence")
    public MigrationEvidenceResponse evidence() {
        return migrationService.evidence();
    }

    /** 签发查询代次，固定当前最新目录代次。 */
    @PostMapping("/query-generations")
    @ResponseStatus(HttpStatus.CREATED)
    public QueryGenerationResponse issueQueryGeneration() {
        return migrationService.issueQueryGeneration();
    }

    /** 固定查询代次的批量查询，不允许混读旧新用途。 */
    @PostMapping("/records/batch-query")
    public BatchQueryResponse batchQuery(@Valid @RequestBody BatchQueryRequest request) {
        return migrationService.batchQuery(request);
    }
}
