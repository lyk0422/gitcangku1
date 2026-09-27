package com.example.starter.evidence;

import com.example.starter.evidence.dto.BatchExamReservationRequest;
import com.example.starter.evidence.dto.BenchCreateRequest;
import com.example.starter.evidence.dto.BenchScheduleView;
import com.example.starter.evidence.dto.BenchView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ExamCancelRequest;
import com.example.starter.evidence.dto.ExamCompleteRequest;
import com.example.starter.evidence.dto.ExamReservationCreateRequest;
import com.example.starter.evidence.dto.ExamReservationDetailView;
import com.example.starter.evidence.dto.ExamReservationView;
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
import java.util.List;

/**
 * 证物检验预约 API。检验室操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409。
 * 所有时段为 UTC 左闭右开区间。
 */
@RestController
@RequestMapping("/api/exam")
@Validated
public class ExamController {

    private final ExamService examService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ExamController(ExamService examService, IdempotencyAdvisor idempotencyAdvisor) {
        this.examService = examService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 注册检验台：容量为同一时段可承接的预约数上限。
     */
    @PostMapping("/benches")
    public ResponseEntity<String> createBench(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                              @Valid @RequestBody BenchCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_BENCH_CREATE, actorId,
                request.benchKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.createBench(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询全部检验台（按注册顺序）。
     */
    @GetMapping("/benches")
    public List<BenchView> listBenches() {
        return examService.listBenches();
    }

    /**
     * 检验台时段诊断：容量上限、窗口内进行中预约实际数与剩余差额。
     * 窗口为 UTC 左闭右开区间 [from, to)；缺省为当前时刻起 24 小时。
     */
    @GetMapping("/benches/{benchKey}/schedule")
    public BenchScheduleView benchSchedule(@PathVariable String benchKey,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                           LocalDateTime from,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                           LocalDateTime to) {
        return examService.benchSchedule(benchKey, from, to);
    }

    /**
     * 创建单条检验预约：证物须在库且封签正常，时段合法且台位容量充足。
     */
    @PostMapping("/reservations")
    public ResponseEntity<String> reserve(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                          @Valid @RequestBody ExamReservationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_RESERVE, actorId,
                request.evidenceKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.reserve(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 批量检验预约：先校验全部台位与证物，任一失败整批不落库。
     */
    @PostMapping("/reservations/batch")
    public ResponseEntity<String> reserveBatch(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                               @Valid @RequestBody BatchExamReservationRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_RESERVE_BATCH, actorId,
                null, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.reserveBatch(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 开始检验：证物转为 EXAMINATION，锁定原封签版本并追加临时链记录。
     */
    @PostMapping("/reservations/{reservationKey}/start")
    public ResponseEntity<String> start(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String reservationKey,
                                        @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_START, actorId,
                reservationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.start(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 完成检验：必须记录结果摘要与新封签校验，证物回到在库。
     */
    @PostMapping("/reservations/{reservationKey}/complete")
    public ResponseEntity<String> complete(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String reservationKey,
                                           @Valid @RequestBody ExamCompleteRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_COMPLETE, actorId,
                reservationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.complete(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消预约：仅未开始的预约可取消，取消后释放检验台与证物占用。
     */
    @PostMapping("/reservations/{reservationKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String reservationKey,
                                         @Valid @RequestBody ExamCancelRequest request) {
        String hash = idempotencyAdvisor.hash(ExamService.OP_EXAM_CANCEL, actorId,
                reservationKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.cancel(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 预约明细：预约当前状态 + 完整事件链。
     */
    @GetMapping("/reservations/{reservationKey}")
    public ExamReservationDetailView getReservation(@PathVariable String reservationKey) {
        return examService.getReservation(reservationKey);
    }

    /**
     * 按证物查询全部检验预约历史（按发生顺序）。
     */
    @GetMapping("/reservations/by-evidence/{evidenceKey}")
    public List<ExamReservationView> listByEvidence(@PathVariable String evidenceKey) {
        return examService.listByEvidence(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
