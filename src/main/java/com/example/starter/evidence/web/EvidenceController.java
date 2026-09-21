package com.example.starter.evidence.web;

import com.example.starter.evidence.domain.CommandType;
import com.example.starter.evidence.service.CommandExecutor;
import com.example.starter.evidence.service.CommandResult;
import com.example.starter.evidence.service.EvidenceService;
import com.example.starter.evidence.web.dto.CommandRequest;
import com.example.starter.evidence.web.dto.CustodyChainResponse;
import com.example.starter.evidence.web.dto.EvidenceResponse;
import com.example.starter.evidence.web.dto.IntakeRequest;
import com.example.starter.evidence.web.dto.SealCheckRequest;
import com.example.starter.evidence.web.dto.TransferInitiateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
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

import java.util.List;

/**
 * 证物封存交接 API。所有变更操作通过 X-Actor-Id 识别操作人，并携带 commandKey 幂等。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class EvidenceController {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final EvidenceService evidenceService;
    private final CommandExecutor commandExecutor;

    public EvidenceController(EvidenceService evidenceService, CommandExecutor commandExecutor) {
        this.evidenceService = evidenceService;
        this.commandExecutor = commandExecutor;
    }

    /**
     * 证物入库，初始状态 SEALED。
     */
    @PostMapping
    public ResponseEntity<Object> intake(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody IntakeRequest request) {
        String fingerprint = CommandExecutor.fingerprintOf(
                CommandType.INTAKE.name(), actorId, request.evidenceKey(), request.caseKey(),
                request.category(), request.sealNo(), request.custodianId());
        CommandResult result = commandExecutor.execute(
                request.commandKey(), CommandType.INTAKE, actorId, fingerprint,
                () -> new CommandResult(HttpStatus.CREATED.value(),
                        evidenceService.intake(actorId, request)));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 发起交接：仅当前保管人，指定一名不同接收人，进入 TRANSFER_PENDING。
     */
    @PostMapping("/{evidenceKey}/transfers")
    public ResponseEntity<Object> initiateTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody TransferInitiateRequest request) {
        String fingerprint = CommandExecutor.fingerprintOf(
                CommandType.TRANSFER_INITIATE.name(), actorId, evidenceKey, request.toCustodianId());
        CommandResult result = commandExecutor.execute(
                request.commandKey(), CommandType.TRANSFER_INITIATE, actorId, fingerprint,
                () -> new CommandResult(HttpStatus.CREATED.value(),
                        evidenceService.initiateTransfer(actorId, evidenceKey, request)));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 接受交接：仅指定接收人，保管人原子切换并回到 SEALED。
     */
    @PostMapping("/{evidenceKey}/transfers/accept")
    public ResponseEntity<Object> acceptTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody CommandRequest request) {
        String fingerprint = CommandExecutor.fingerprintOf(
                CommandType.TRANSFER_ACCEPT.name(), actorId, evidenceKey);
        CommandResult result = commandExecutor.execute(
                request.commandKey(), CommandType.TRANSFER_ACCEPT, actorId, fingerprint,
                () -> new CommandResult(HttpStatus.OK.value(),
                        evidenceService.acceptTransfer(actorId, evidenceKey)));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 取消交接：仅原保管人（发起方），证物回到 SEALED。
     */
    @PostMapping("/{evidenceKey}/transfers/cancel")
    public ResponseEntity<Object> cancelTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody CommandRequest request) {
        String fingerprint = CommandExecutor.fingerprintOf(
                CommandType.TRANSFER_CANCEL.name(), actorId, evidenceKey);
        CommandResult result = commandExecutor.execute(
                request.commandKey(), CommandType.TRANSFER_CANCEL, actorId, fingerprint,
                () -> new CommandResult(HttpStatus.OK.value(),
                        evidenceService.cancelTransfer(actorId, evidenceKey)));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 封条核验：仅当前保管人；PASS 追加不可变记录，FAIL 进入 SEAL_BROKEN 终态。
     */
    @PostMapping("/{evidenceKey}/seal-checks")
    public ResponseEntity<Object> sealCheck(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String evidenceKey,
            @Valid @RequestBody SealCheckRequest request) {
        String fingerprint = CommandExecutor.fingerprintOf(
                CommandType.SEAL_CHECK.name(), actorId, evidenceKey,
                request.result().name(), request.detail() == null ? "" : request.detail());
        CommandResult result = commandExecutor.execute(
                request.commandKey(), CommandType.SEAL_CHECK, actorId, fingerprint,
                () -> new CommandResult(HttpStatus.CREATED.value(),
                        evidenceService.sealCheck(actorId, evidenceKey, request)));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 当前可交接证物：指定操作人保管且状态 SEALED 的证物列表。
     */
    @GetMapping("/transferable")
    public List<EvidenceResponse> transferable(@RequestParam @NotBlank String actorId) {
        return evidenceService.findTransferable(actorId);
    }

    /**
     * 完整保管链：入库、交接、核验事件按时间升序。
     */
    @GetMapping("/{evidenceKey}/custody-chain")
    public CustodyChainResponse custodyChain(@PathVariable String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey);
    }
}
