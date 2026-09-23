package com.example.starter.aliquot;

import com.example.starter.aliquot.dto.AliquotApplyRequest;
import com.example.starter.aliquot.dto.AliquotConsumptionView;
import com.example.starter.aliquot.dto.AliquotFirstConfirmRequest;
import com.example.starter.aliquot.dto.AliquotItemInput;
import com.example.starter.aliquot.dto.AliquotRejectRequest;
import com.example.starter.aliquot.dto.AliquotSecondConfirmRequest;
import com.example.starter.aliquot.dto.SampleBalanceView;
import com.example.starter.aliquot.dto.SampleRegisterRequest;
import com.example.starter.evidence.IdempotencyAdvisor;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.dto.CommandRequest;
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

import java.util.Comparator;
import java.util.List;

/**
 * 多母样联合取样 API。所有操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey（申请时即 requestId）保证幂等：
 * 同键同参（母样集合换序视为同参）重放返回首次结果，同键异参返回 409，失败不占用键。
 */
@RestController
@RequestMapping("/api/aliquots")
@Validated
public class AliquotController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final AliquotService aliquotService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public AliquotController(AliquotService aliquotService,
                             IdempotencyAdvisor idempotencyAdvisor) {
        this.aliquotService = aliquotService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记母样不可修改的正整数总量和单位（母样首次参与联合取样前调用一次）。
     */
    @PostMapping("/samples/{sampleKey}")
    public ResponseEntity<String> registerSample(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String sampleKey,
            @Valid @RequestBody SampleRegisterRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_SAMPLE_REGISTER, actorId,
                sampleKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.registerSample(actorId, sampleKey, request, hash));
        return toEntity(response);
    }

    /**
     * 联合取样申请：从 2～20 件不同母样各取正整数数量，原子预留，指定唯一 aliquotKey。
     */
    @PostMapping
    public ResponseEntity<String> apply(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @Valid @RequestBody AliquotApplyRequest request) {
        // 幂等指纹按 sampleKey 排序后计算，使“同参集合换序”成为同参重放。
        AliquotApplyRequest canonical = canonical(request);
        String hash = idempotencyAdvisor.hash(AliquotService.OP_ALIQUOT_APPLY, actorId,
                canonical.aliquotKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.apply(actorId, canonical, hash));
        return toEntity(response);
    }

    /**
     * 第一次审核确认：审核人不能是任一母样当前保管人。
     */
    @PostMapping("/{aliquotKey}/confirmations/first")
    public ResponseEntity<String> firstConfirm(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String aliquotKey,
            @Valid @RequestBody AliquotFirstConfirmRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_ALIQUOT_FIRST_CONFIRM, actorId,
                aliquotKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.firstConfirm(actorId, aliquotKey, request, hash));
        return toEntity(response);
    }

    /**
     * 第二次审核确认：两名审核人不同，携带申请版本与全部母样版本；成功后原子耗用并生成子样。
     */
    @PostMapping("/{aliquotKey}/confirmations/second")
    public ResponseEntity<String> secondConfirm(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String aliquotKey,
            @Valid @RequestBody AliquotSecondConfirmRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_ALIQUOT_SECOND_CONFIRM, actorId,
                aliquotKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.secondConfirm(actorId, aliquotKey, request, hash));
        return toEntity(response);
    }

    /**
     * 审核拒绝：一次释放全部预留，单据终结。
     */
    @PostMapping("/{aliquotKey}/rejections")
    public ResponseEntity<String> reject(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String aliquotKey,
            @Valid @RequestBody AliquotRejectRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_ALIQUOT_REJECT, actorId,
                aliquotKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.reject(actorId, aliquotKey, request, hash));
        return toEntity(response);
    }

    /**
     * 审核前取消：仅申请人，一次释放全部预留。
     */
    @PostMapping("/{aliquotKey}/cancellations")
    public ResponseEntity<String> cancel(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String aliquotKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_ALIQUOT_CANCEL, actorId,
                aliquotKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.cancel(actorId, aliquotKey, request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 查询取样单详情：明细、耗用映射与审核历史，只读。
     */
    @GetMapping("/{aliquotKey}")
    public AliquotConsumptionView.Detail getDetail(@PathVariable String aliquotKey) {
        return aliquotService.getDetail(aliquotKey);
    }

    /**
     * 查询母样余额：总量、预留、耗用与可用余额，只读。
     */
    @GetMapping("/samples/{sampleKey}/balance")
    public SampleBalanceView getBalance(@PathVariable String sampleKey) {
        return aliquotService.getBalance(sampleKey);
    }

    private AliquotApplyRequest canonical(AliquotApplyRequest request) {
        List<AliquotItemInput> sorted = request.items().stream()
                .sorted(Comparator.comparing(AliquotItemInput::sampleKey))
                .toList();
        return new AliquotApplyRequest(request.commandKey(), request.aliquotKey(), sorted);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
