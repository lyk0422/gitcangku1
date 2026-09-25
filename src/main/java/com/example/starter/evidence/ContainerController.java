package com.example.starter.evidence;

import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerLoadRequest;
import com.example.starter.evidence.dto.ContainerReviewRequest;
import com.example.starter.evidence.dto.ContainerUnloadRequest;
import com.example.starter.evidence.dto.ContainerView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.LoanEligibility;
import com.example.starter.evidence.dto.ReviewView;
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
 * 封存容器巡检 API。操作人通过 X-Actor-Id 请求头提供；写操作携带 commandKey 幂等。
 * 装载集合换序视为同参（规范化去重排序后计算指纹）；
 * inspectKey 指纹含检查人、容器版本、实际时刻、结果和说明，同键同参重放首次完整结果，失败不占键。
 */
@RestController
@RequestMapping("/api/containers")
@Validated
public class ContainerController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ContainerService containerService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ContainerController(ContainerService containerService,
                               IdempotencyAdvisor idempotencyAdvisor) {
        this.containerService = containerService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建封存容器：初始 SEALED，版本 0。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_CREATE, actorId,
                request.containerKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.createContainer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 覆盖式装载证物：集合去重排序后视为同一组参数；FAIL 容器返回 409。
     */
    @PostMapping("/{containerKey}/load")
    public ResponseEntity<String> load(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String containerKey,
                                       @Valid @RequestBody ContainerLoadRequest request) {
        ContainerLoadRequest normalized = normalize(request);
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_LOAD, actorId,
                containerKey, normalized);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.load(actorId, containerKey, normalized, hash));
        return toEntity(response);
    }

    /**
     * 移出证物：FAIL 容器返回 409。
     */
    @PostMapping("/{containerKey}/unload")
    public ResponseEntity<String> unload(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerUnloadRequest request) {
        ContainerUnloadRequest normalized = new ContainerUnloadRequest(request.commandKey(),
                request.evidenceKeys().stream().distinct().sorted().toList());
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_UNLOAD, actorId,
                containerKey, normalized);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.unload(actorId, containerKey, normalized, hash));
        return toEntity(response);
    }

    /**
     * 容器巡检：PASS 更新下次巡检时刻；FAIL 单事务批量标记待核验并写逐件快照。
     * 巡检的幂等裁决在服务内按 inspectKey 语义完成（指纹含容器版本），不走外层 guard。
     */
    @PostMapping("/{containerKey}/inspections")
    public ResponseEntity<String> inspect(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String containerKey,
                                          @Valid @RequestBody ContainerInspectionRequest request) {
        StoredResponse response = containerService.inspect(actorId, containerKey, request);
        return toEntity(response);
    }

    /**
     * 双人复核封签：两名互不相同且异于 FAIL 检查人的保管人各提交一次后容器恢复。
     */
    @PostMapping("/{containerKey}/reviews")
    public ResponseEntity<String> review(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerReviewRequest request) {
        String hash = idempotencyAdvisor.hash(ContainerService.OP_CONTAINER_REVIEW, actorId,
                containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> containerService.review(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询容器当前状态与装载集合。
     */
    @GetMapping("/{containerKey}")
    public ContainerView getContainer(@PathVariable String containerKey) {
        return containerService.getContainer(containerKey);
    }

    /**
     * 查询容器全部巡检记录与逐件不可变快照。
     */
    @GetMapping("/{containerKey}/inspections")
    public List<ContainerInspectionView> listInspections(@PathVariable String containerKey) {
        return containerService.listInspections(containerKey);
    }

    /**
     * 查询容器内待核验证物。
     */
    @GetMapping("/{containerKey}/pending-verification")
    public List<EvidenceView> listPendingVerification(@PathVariable String containerKey) {
        return containerService.listPendingVerification(containerKey);
    }

    /**
     * 查询容器全部复核记录。
     */
    @GetMapping("/{containerKey}/reviews")
    public List<ReviewView> listReviews(@PathVariable String containerKey) {
        return containerService.listReviews(containerKey);
    }

    /**
     * 查询证物借出门禁与阻断原因。
     */
    @GetMapping("/loan-eligibility/{evidenceKey}")
    public LoanEligibility loanEligibility(@PathVariable String evidenceKey) {
        return containerService.loanEligibility(evidenceKey);
    }

    private ContainerLoadRequest normalize(ContainerLoadRequest request) {
        return new ContainerLoadRequest(request.commandKey(),
                request.evidenceKeys().stream().distinct().sorted().toList());
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
