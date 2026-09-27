package com.example.starter.evidence;

import com.example.starter.evidence.dto.BenchOccupancyView;
import com.example.starter.evidence.dto.ExamCancelRequest;
import com.example.starter.evidence.dto.ExamCompleteRequest;
import com.example.starter.evidence.dto.ExamReserveRequest;
import com.example.starter.evidence.dto.ExamReserveRequest.ExamReservationItemRequest;
import com.example.starter.evidence.dto.ExamReservationView;
import com.example.starter.evidence.dto.ExamStartRequest;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证物检验预约 API。操作人（检验室）通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey（requestId）保证幂等：同键同参重放返回首次完整响应快照，同键改参返回 409。
 * 所有时间均为 UTC、区间左闭右开；查询接口只读，不改变状态。
 */
@RestController
@RequestMapping("/api")
@Validated
public class ExamReservationController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ExamReservationService examService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ExamReservationController(ExamReservationService examService,
                                     IdempotencyAdvisor idempotencyAdvisor) {
        this.examService = examService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 批量创建检验预约：先校验全部台位与证物，任一失败整批回滚，不留下占用。
     */
    @PostMapping("/exam-reservations")
    public ResponseEntity<String> reserve(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @Valid @RequestBody ExamReserveRequest request) {
        String hash = idempotencyAdvisor.hashCanonical(
                ExamReservationService.OP_EXAM_RESERVE, actorId, canonicalReservePayload(request));
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.reserve(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 开始检验：证物转 EXAMINATION、写入临时链记录并锁定原封签版本。
     */
    @PostMapping("/exam-reservations/{reservationKey}/start")
    public ResponseEntity<String> start(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String reservationKey,
                                        @Valid @RequestBody ExamStartRequest request) {
        String hash = idempotencyAdvisor.hashCanonical(ExamReservationService.OP_EXAM_START, actorId,
                payload("reservationKey", reservationKey));
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.start(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 完成检验：记录结果摘要与新封签校验，封签版本递增，证物回到在库 SEALED。
     */
    @PostMapping("/exam-reservations/{reservationKey}/complete")
    public ResponseEntity<String> complete(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String reservationKey,
                                           @Valid @RequestBody ExamCompleteRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationKey", reservationKey);
        payload.put("resultSummary", request.resultSummary());
        payload.put("newSealNo", request.newSealNo());
        payload.put("newSealCheck", request.newSealCheck());
        String hash = idempotencyAdvisor.hashCanonical(
                ExamReservationService.OP_EXAM_COMPLETE, actorId, payload);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.complete(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消未开始预约并释放台位；已开始或已终结的预约返回 409。
     */
    @PostMapping("/exam-reservations/{reservationKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String reservationKey,
                                         @Valid @RequestBody ExamCancelRequest request) {
        String hash = idempotencyAdvisor.hashCanonical(ExamReservationService.OP_EXAM_CANCEL, actorId,
                payload("reservationKey", reservationKey));
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> examService.cancel(actorId, reservationKey, request, hash));
        return toEntity(response);
    }

    /**
     * 预约明细查询（只读）。
     */
    @GetMapping("/exam-reservations/{reservationKey}")
    public ExamReservationView getReservation(@PathVariable String reservationKey) {
        return examService.getReservation(reservationKey);
    }

    /**
     * 证物检验历史：全部预约与不可变保管链事件，按发生顺序（只读）。
     */
    @GetMapping("/evidence/{evidenceKey}/exam-history")
    public ExamReservationService.ExamHistoryView history(@PathVariable String evidenceKey) {
        return examService.history(evidenceKey);
    }

    /**
     * 台位占用诊断：实际占用、容量要求、差额与重叠明细（UTC 左闭右开，只读）。
     */
    @GetMapping("/exam-benches/{benchKey}/occupancy")
    public BenchOccupancyView occupancy(
            @PathVariable String benchKey,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
        return examService.occupancy(benchKey, startAt, endAt);
    }

    /**
     * 构造批量预约的规范化指纹载荷：明细集合按证物键、台位、时段、预约键排序，
     * 保证集合顺序不影响指纹；禁用未转义分隔符拼接，全部字段经 JSON 序列化转义。
     */
    private Map<String, Object> canonicalReservePayload(ExamReserveRequest request) {
        List<Map<String, Object>> items = request.items().stream()
                .sorted(Comparator.comparing(ExamReservationItemRequest::evidenceKey)
                        .thenComparing(ExamReservationItemRequest::benchKey)
                        .thenComparing(ExamReservationItemRequest::startAt)
                        .thenComparing(ExamReservationItemRequest::endAt)
                        .thenComparing(ExamReservationItemRequest::reservationKey))
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("reservationKey", item.reservationKey());
                    entry.put("evidenceKey", item.evidenceKey());
                    entry.put("benchKey", item.benchKey());
                    entry.put("startAt", item.startAt());
                    entry.put("endAt", item.endAt());
                    return entry;
                })
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        return payload;
    }

    private Map<String, Object> payload(String key, String value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
