package com.example.starter.evidence;

import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MediaView;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationDecideRequest;
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
 * 证物介质迁移 API。保管人登记初始 ACTIVE 介质、创建/切换/取消迁移；
 * 非保管人见证者提交新介质摘要完成验证。写操作携带 commandKey 保证幂等，
 * 幂等身份含操作人、操作、资源（证物键/迁移键）、全参数及期望版本。
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
     * 登记介质：仅当前保管人；证物尚无 ACTIVE 介质时登记即为当前读取来源。
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
     * 创建迁移：仅当前保管人，证物须 SEALED；固定源介质版本与新介质号，新介质 UNVERIFIED。
     */
    @PostMapping("/{evidenceKey}/migrations")
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
     * 见证验证：仅非保管人见证者；摘要等于源摘要才 VERIFIED，错摘要 422 可另键重提。
     */
    @PostMapping("/{evidenceKey}/migrations/{migrationKey}/verify")
    public ResponseEntity<String> verifyMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @PathVariable String migrationKey,
            @Valid @RequestBody MigrationVerifyRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_VERIFY, actorId,
                evidenceKey + "/" + migrationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.verifyMigration(actorId, evidenceKey, migrationKey,
                        request, hash));
        return toEntity(response);
    }

    /**
     * 保管人显式切换：新介质转 ACTIVE、源介质转 RETIRED，同事务保证恰好一个 ACTIVE。
     */
    @PostMapping("/{evidenceKey}/migrations/{migrationKey}/switch")
    public ResponseEntity<String> switchMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @PathVariable String migrationKey,
            @Valid @RequestBody MigrationDecideRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_SWITCH, actorId,
                evidenceKey + "/" + migrationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.switchMigration(actorId, evidenceKey, migrationKey,
                        request, hash));
        return toEntity(response);
    }

    /**
     * 取消迁移：仅当前保管人；源副本不变，新介质作废。
     */
    @PostMapping("/{evidenceKey}/migrations/{migrationKey}/cancel")
    public ResponseEntity<String> cancelMigration(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @PathVariable String migrationKey,
            @Valid @RequestBody MigrationDecideRequest request) {
        String hash = idempotencyAdvisor.hash(MediaMigrationService.OP_MIGRATION_CANCEL, actorId,
                evidenceKey + "/" + migrationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> migrationService.cancelMigration(actorId, evidenceKey, migrationKey,
                        request, hash));
        return toEntity(response);
    }

    /**
     * 查询证物全部介质（含 RETIRED 历史元数据）。
     */
    @GetMapping("/{evidenceKey}/media")
    public List<MediaView> listMedia(@PathVariable String evidenceKey) {
        return migrationService.listMedia(evidenceKey);
    }

    /**
     * 查询当前读取来源（唯一 ACTIVE 介质）。
     */
    @GetMapping("/{evidenceKey}/media/current")
    public MediaView currentMedia(@PathVariable String evidenceKey) {
        return migrationService.currentMedia(evidenceKey);
    }

    /**
     * 查询证物全部迁移记录。
     */
    @GetMapping("/{evidenceKey}/migrations")
    public List<MigrationView> listMigrations(@PathVariable String evidenceKey) {
        return migrationService.listMigrations(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
