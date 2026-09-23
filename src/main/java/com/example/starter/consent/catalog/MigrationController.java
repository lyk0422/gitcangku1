package com.example.starter.consent.catalog;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.catalog.dto.ActivateMigrationRequest;
import com.example.starter.consent.catalog.dto.ActivateMigrationResponse;
import com.example.starter.consent.catalog.dto.BatchQueryRequest;
import com.example.starter.consent.catalog.dto.BatchQueryResponse;
import com.example.starter.consent.catalog.dto.MigrationEvidenceResponse;
import com.example.starter.consent.catalog.dto.MigrationPreviewResponse;
import com.example.starter.consent.catalog.dto.MigrationProposalRequest;
import com.example.starter.consent.catalog.dto.QueryGenerationRequest;
import com.example.starter.consent.catalog.dto.QueryGenerationResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 用途目录拆分迁移与查询代次隔离 API。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * 迁移预览：只读，列出全部有效授权、已撤回授权与数据记录及映射结果。
     */
    @PostMapping("/catalog/migrations/preview")
    public MigrationPreviewResponse preview(@Valid @RequestBody MigrationProposalRequest request) {
        return migrationService.preview(request);
    }

    /**
     * 迁移激活：整单事务提交，发布新 catalogGeneration。
     */
    @PostMapping("/catalog/migrations/activations")
    public ActivateMigrationResponse activate(@Valid @RequestBody ActivateMigrationRequest request) {
        return migrationService.activate(request);
    }

    /**
     * 迁移证据查询：只读，明细稳定排序。
     */
    @GetMapping("/catalog/migrations/{migrationKey}/evidence")
    public MigrationEvidenceResponse evidence(@PathVariable @NotBlank String migrationKey) {
        return migrationService.evidence(migrationKey);
    }

    /**
     * 签发固定 catalogGeneration 的查询代次令牌。
     */
    @PostMapping("/query-generations")
    public QueryGenerationResponse issueQueryGeneration(@Valid @RequestBody QueryGenerationRequest request) {
        return migrationService.issueQueryGeneration(request);
    }

    /**
     * 固定查询代次的批量查询，不允许混读旧新用途。
     */
    @PostMapping("/records/batch-query")
    public BatchQueryResponse batchQuery(@Valid @RequestBody BatchQueryRequest request) {
        return migrationService.batchQuery(request);
    }
}
