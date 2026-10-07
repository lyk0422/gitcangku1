package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MediaView;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationVerifyRequest;
import com.example.starter.evidence.dto.MigrationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 介质迁移 API。证物更换存储介质时先验证新副本再作废旧副本，不改变保管人。
 * 操作人通过 X-Actor-Id 请求头提供；写操作携带 commandKey 保证幂等：
 * 同键同参重放返回首次结果，同键改参返回 409，失败可复用键。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class MediaMigrationController {

    private final MediaMigrationService migrationService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public MediaMigrationController(MediaMigrationService migrationService,
                                    IdempotencyAdvisor idempotencyAdvisor) {
        this.migrationService = migrationService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记首个介质：仅当前保管人；登记即为 ACTIVE，保存合成内容的 SHA-256。
     */
    @PostMapping("/{evidenceKey}/media")
    public ResponseEntity<String> registerMedia(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody MediaRegisterRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MEDIA_REGISTER, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.registerMedia(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 创建迁移：仅当前保管人，证物须正常 SEALED 且无借出、无待交接；
     * 固定源介质版本与新介质号，新介质仅为 UNVERIFIED。
     */
    @PostMapping("/{evidenceKey}/media-migrations")
    public ResponseEntity<String> createMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody MigrationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_CREATE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.createMigration(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 见证验证：仅非保管人见证者；摘要必须等于源摘要才 VERIFIED，错摘要 422 可另键重提。
     */
    @PostMapping("/{evidenceKey}/media-migrations/verify")
    public ResponseEntity<String> verifyMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody MigrationVerifyRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_VERIFY, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.verifyMigration(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 显式切换：仅当前保管人；同事务内新介质转 ACTIVE、旧介质转 RETIRED，恰好一个 ACTIVE。
     */
    @PostMapping("/{evidenceKey}/media-migrations/switch")
    public ResponseEntity<String> switchMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_SWITCH, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.switchMigration(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消迁移：仅当前保管人；取消不改变源副本。
     */
    @PostMapping("/{evidenceKey}/media-migrations/cancel")
    public ResponseEntity<String> cancelMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_CANCEL, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.cancelMigration(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询证物全部介质（含 RETIRED 历史）。
     */
    @GetMapping("/{evidenceKey}/media")
    public List<MediaView> listMedia(@PathVariable String evidenceKey) {
        return migrationService.listMedia(evidenceKey);
    }

    /**
     * 查询当前读取来源：仅返回 ACTIVE 介质。
     */
    @GetMapping("/{evidenceKey}/media/current")
    public MediaView currentMedia(@PathVariable String evidenceKey) {
        return migrationService.currentMedia(evidenceKey);
    }

    /**
     * 查询证物全部迁移记录（按发生顺序）。
     */
    @GetMapping("/{evidenceKey}/media-migrations")
    public List<MigrationView> listMigrations(@PathVariable String evidenceKey) {
        return migrationService.listMigrations(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
