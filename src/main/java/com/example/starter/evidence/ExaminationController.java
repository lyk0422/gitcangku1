package com.example.starter.evidence;

import com.example.starter.evidence.dto.BenchRegisterRequest;
import com.example.starter.evidence.dto.BenchView;
import com.example.starter.evidence.dto.ExaminationCancelRequest;
import com.example.starter.evidence.dto.ExaminationCompleteRequest;
import com.example.starter.evidence.dto.ExaminationEventView;
import com.example.starter.evidence.dto.ExaminationStartRequest;
import com.example.starter.evidence.dto.ReservationBatchRequest;
import com.example.starter.evidence.dto.ReservationCreateRequest;
import com.example.starter.evidence.dto.ReservationView;
import com.example.starter.evidence.dto.SealVersionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证物检验预约 API。时段统一 UTC 左闭右开；写操作携带 commandKey 保证幂等：
 * 同键同参重放返回首次结果，同键改参返回 409，失败不占用键。
 * 所有查询为只读，不改变状态。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class ExaminationController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ExaminationService examinationService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ExaminationController(ExaminationService examinationService,
                                 IdempotencyAdvisor idempotencyAdvisor) {
        this.examinationService = examinationService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 注册检验台（含容量）。
     */
    @PostMapping("/lab-benches")
    public ResponseEntity<String> registerBench(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @Valid @RequestBody BenchRegisterRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_BENCH_REGISTER, actorId,
                Map.of("benchKey", request.benchKey()), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.registerBench(actorId,
                        new ExaminationService.BenchRegisterRequestInput(
                                request.commandKey(), request.benchKey(), request.name(),
                                request.capacity()), hash));
        return toEntity(response);
    }

    /**
     * 查询全部检验台（只读）。
     */
    @GetMapping("/lab-benches")
    public List<BenchView> listBenches() {
        return examinationService.listBenches();
    }

    /**
     * 为在库且封签正常的证物创建单个 UTC 检验预约。
     */
    @PostMapping("/{evidenceKey}/examination-reservations")
    public ResponseEntity<String> reserve(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String evidenceKey,
                                          @Valid @RequestBody ReservationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_EXAM_RESERVE, actorId,
                Map.of("evidenceKey", evidenceKey, "reservationKey", request.reservationKey()),
                request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.reserve(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 批量创建检验预约：先校验全部台位与证物，任一失败整批回滚，不留下占用。
     * items 视为无序集合，指纹按业务键规范化排序。
     */
    @PostMapping("/examination-reservations/batch")
    public ResponseEntity<String> reserveBatch(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody ReservationBatchRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_EXAM_RESERVE_BATCH, actorId,
                Map.of(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.reserveBatch(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 开始检验：证物转为 EXAMINATION、写入临时链记录并锁定原封签版本。
     */
    @PostMapping("/{evidenceKey}/examination-reservations/{reservationKey}/start")
    public ResponseEntity<String> start(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String evidenceKey,
                                        @PathVariable String reservationKey,
                                        @Valid @RequestBody ExaminationStartRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_EXAM_START, actorId,
                reservationScope(evidenceKey, reservationKey), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.start(actorId, evidenceKey, reservationKey,
                        request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 完成检验：必须提供结果摘要与新封签编号，通过后证物回到在库。
     */
    @PostMapping("/{evidenceKey}/examination-reservations/{reservationKey}/complete")
    public ResponseEntity<String> complete(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String evidenceKey,
                                           @PathVariable String reservationKey,
                                           @Valid @RequestBody ExaminationCompleteRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_EXAM_COMPLETE, actorId,
                reservationScope(evidenceKey, reservationKey), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.complete(actorId, evidenceKey, reservationKey,
                        request.resultSummary(), request.newSealNo(), request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 取消未开始预约并释放台位与证物占用。
     */
    @PostMapping("/{evidenceKey}/examination-reservations/{reservationKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @PathVariable String reservationKey,
                                         @Valid @RequestBody ExaminationCancelRequest request) {
        String hash = idempotencyAdvisor.hash(ExaminationService.OP_EXAM_CANCEL, actorId,
                reservationScope(evidenceKey, reservationKey), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examinationService.cancel(actorId, evidenceKey, reservationKey,
                        request.reason(), request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 查询预约明细（只读）。
     */
    @GetMapping("/examination-reservations/{reservationKey}")
    public ReservationView getReservation(@PathVariable String reservationKey) {
        return examinationService.getReservation(reservationKey);
    }

    /**
     * 按证物查询全部预约历史（按创建顺序，只读）。
     */
    @GetMapping("/{evidenceKey}/examination-reservations")
    public List<ReservationView> listByEvidence(@PathVariable String evidenceKey) {
        return examinationService.listByEvidence(evidenceKey);
    }

    /**
     * 按证物查询封签版本历史（只读）。
     */
    @GetMapping("/{evidenceKey}/seal-versions")
    public List<SealVersionView> listSealVersions(@PathVariable String evidenceKey) {
        return examinationService.listSealVersions(evidenceKey);
    }

    /**
     * 按证物查询检验保管链事件历史（只读）。
     */
    @GetMapping("/{evidenceKey}/examination-events")
    public List<ExaminationEventView> listEvents(@PathVariable String evidenceKey) {
        return examinationService.listEvents(evidenceKey);
    }

    /**
     * 台位诊断：窗口 UTC 左闭右开，返回实际占用、要求容量、剩余差额与利用率比例（只读）。
     */
    @GetMapping("/lab-benches/{benchKey}/diagnostics")
    public ExaminationService.BenchDiagnosticsView diagnostics(
            @PathVariable String benchKey,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return examinationService.benchDiagnostics(benchKey, from, to);
    }

    private Map<String, String> reservationScope(String evidenceKey, String reservationKey) {
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("evidenceKey", evidenceKey);
        scope.put("reservationKey", reservationKey);
        return scope;
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
