package com.example.starter.service;

import com.example.starter.db.AllocationRepository;
import com.example.starter.db.AllocationRow;
import com.example.starter.db.ExperimentRepository;
import com.example.starter.db.ExperimentRow;
import com.example.starter.db.SeatRow;
import com.example.starter.db.UnblindRequestRepository;
import com.example.starter.db.UnblindRequestRow;
import com.example.starter.domain.Actor;
import com.example.starter.domain.AllocationStatus;
import com.example.starter.domain.ExperimentStatus;
import com.example.starter.domain.UnblindStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.error.ExperimentFullException;
import com.example.starter.error.ForbiddenException;
import com.example.starter.error.NotFoundException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.idempotency.OperationType;
import com.example.starter.idempotency.ParamsHasher;
import com.example.starter.idempotency.WriteOutcome;
import com.example.starter.support.BlindCodeGenerator;
import com.example.starter.web.dto.AllocationView;
import com.example.starter.web.dto.ApproveUnblindRequest;
import com.example.starter.web.dto.CloseExperimentRequest;
import com.example.starter.web.dto.CreateExperimentRequest;
import com.example.starter.web.dto.EnrollRequest;
import com.example.starter.web.dto.ExperimentResponse;
import com.example.starter.web.dto.UnblindRequestRequest;
import com.example.starter.web.dto.UnblindRequestView;
import com.example.starter.web.dto.UnblindResultView;
import com.example.starter.web.dto.WithdrawRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 实验盲法分配与受控揭盲业务服务。 */
@Service
public class ExperimentService {

    private static final int BLIND_CODE_ATTEMPTS = 5;

    private final IdempotencyService idempotency;
    private final ExperimentRepository experiments;
    private final AllocationRepository allocations;
    private final UnblindRequestRepository unblindRequests;
    private final BlindCodeGenerator blindCodeGenerator;

    public ExperimentService(IdempotencyService idempotency,
                             ExperimentRepository experiments,
                             AllocationRepository allocations,
                             UnblindRequestRepository unblindRequests,
                             BlindCodeGenerator blindCodeGenerator) {
        this.idempotency = idempotency;
        this.experiments = experiments;
        this.allocations = allocations;
        this.unblindRequests = unblindRequests;
        this.blindCodeGenerator = blindCodeGenerator;
    }

    /** 创建实验：固定 2～8 个区组，每区组 4 席（顺序 A、A、B、B），此后不可改。 */
    public WriteOutcome createExperiment(Actor actor, CreateExperimentRequest request) {
        String hash = ParamsHasher.sha256(OperationType.CREATE_EXPERIMENT, Map.of(
                "experimentId", request.experimentId(),
                "blockCount", request.blockCount()));
        return idempotency.execute(actor, request.requestId(), OperationType.CREATE_EXPERIMENT, hash, () -> {
            Instant now = Instant.now();
            experiments.insert(new ExperimentRow(
                    request.experimentId(), request.blockCount(), ExperimentStatus.OPEN.name(), now));
            experiments.insertSeats(request.experimentId(), request.blockCount());
            ExperimentResponse body = new ExperimentResponse(
                    request.experimentId(),
                    request.blockCount(),
                    request.blockCount() * 4,
                    ExperimentStatus.OPEN.name());
            return new IdempotencyService.BusinessResult(201, body);
        });
    }

    /** 参与者首次登记，原子领取按区组、席位顺序的第一个空位。 */
    public WriteOutcome enroll(Actor actor, String experimentId, EnrollRequest request) {
        String hash = ParamsHasher.sha256(OperationType.ENROLL, Map.of(
                "experimentId", experimentId,
                "participantId", request.participantId()));
        return idempotency.execute(actor, request.requestId(), OperationType.ENROLL, hash, () -> {
            // 锁定实验行，串行化同实验并发登记，保证“第一个空位”不被并发重复领取
            ExperimentRow experiment = experiments.findByIdForUpdate(experimentId)
                    .orElseThrow(() -> new NotFoundException("experiment not found: " + experimentId));
            if (ExperimentStatus.CLOSED.name().equals(experiment.status())) {
                throw new ConflictException("experiment is closed");
            }
            if (allocations.find(experimentId, request.participantId()).isPresent()) {
                throw new ConflictException("participant already holds a seat");
            }
            SeatRow seat = experiments.findFirstFreeSeat(experimentId)
                    .orElseThrow(() -> new ExperimentFullException("experiment has no free seat"));
            String blindCode = nextUniqueBlindCode();
            Instant now = Instant.now();
            AllocationRow row = new AllocationRow(
                    0L, experimentId, request.participantId(), seat.blockNo(), seat.seatNo(),
                    blindCode, AllocationStatus.ENROLLED.name(), now, now);
            allocations.insert(row);
            AllocationView body = new AllocationView(
                    blindCode, seat.blockNo(), request.participantId(), AllocationStatus.ENROLLED.name());
            return new IdempotencyService.BusinessResult(201, body);
        });
    }

    /** 参与者退组：不释放席位、不重排已有分配，普通查询返回退组状态。 */
    public WriteOutcome withdraw(Actor actor, String experimentId, WithdrawRequest request) {
        String hash = ParamsHasher.sha256(OperationType.WITHDRAW, Map.of(
                "experimentId", experimentId,
                "participantId", request.participantId()));
        return idempotency.execute(actor, request.requestId(), OperationType.WITHDRAW, hash, () -> {
            AllocationRow allocation = allocations.find(experimentId, request.participantId())
                    .orElseThrow(() -> new NotFoundException("allocation not found"));
            int updated = allocations.markWithdrawnIfEnrolled(allocation.id());
            if (updated == 0) {
                throw new ConflictException("participant has already withdrawn");
            }
            AllocationView body = new AllocationView(
                    allocation.blindCode(), allocation.blockNo(),
                    allocation.participantId(), AllocationStatus.WITHDRAWN.name());
            return new IdempotencyService.BusinessResult(200, body);
        });
    }

    /** 关闭实验：关闭后拒绝新增分配；重复关闭返回 409。 */
    public WriteOutcome close(Actor actor, String experimentId, CloseExperimentRequest request) {
        String hash = ParamsHasher.sha256(OperationType.CLOSE_EXPERIMENT, Map.of(
                "experimentId", experimentId));
        return idempotency.execute(actor, request.requestId(), OperationType.CLOSE_EXPERIMENT, hash, () -> {
            ExperimentRow experiment = experiments.findByIdForUpdate(experimentId)
                    .orElseThrow(() -> new NotFoundException("experiment not found: " + experimentId));
            if (!experiments.closeIfOpen(experimentId)) {
                throw new ConflictException("experiment is already closed");
            }
            ExperimentResponse body = new ExperimentResponse(
                    experiment.id(), experiment.blockCount(),
                    experiment.blockCount() * 4, ExperimentStatus.CLOSED.name());
            return new IdempotencyService.BusinessResult(200, body);
        });
    }

    /** 普通查询：仅盲码、区组号、参与者编号与状态，不含处理代码与席位序号。 */
    public AllocationView getAllocation(Actor actor, String experimentId, String participantId) {
        if (experiments.findById(experimentId).isEmpty()) {
            throw new NotFoundException("experiment not found: " + experimentId);
        }
        AllocationRow allocation = allocations.find(experimentId, participantId)
                .orElseThrow(() -> new NotFoundException("allocation not found"));
        return new AllocationView(
                allocation.blindCode(),
                allocation.blockNo(),
                allocation.participantId(),
                allocation.status());
    }

    /** 协调员为已分配参与者提出带原因的揭盲申请；同一分配至多一个申请。 */
    public WriteOutcome requestUnblind(Actor actor, String experimentId, UnblindRequestRequest request) {
        String hash = ParamsHasher.sha256(OperationType.REQUEST_UNBLIND, Map.of(
                "experimentId", experimentId,
                "participantId", request.participantId(),
                "reason", request.reason()));
        return idempotency.execute(actor, request.requestId(), OperationType.REQUEST_UNBLIND, hash, () -> {
            if (experiments.findById(experimentId).isEmpty()) {
                throw new NotFoundException("experiment not found: " + experimentId);
            }
            AllocationRow allocation = allocations.find(experimentId, request.participantId())
                    .orElseThrow(() -> new NotFoundException("allocation not found"));
            Optional<UnblindRequestRow> existing = unblindRequests.findByAllocation(allocation.id());
            if (existing.isPresent()) {
                throw new ConflictException("an unblind request for this allocation already exists");
            }
            Instant now = Instant.now();
            UnblindRequestRow row = new UnblindRequestRow(
                    0L, experimentId, allocation.id(), request.participantId(),
                    actor.actorId(), request.reason(), UnblindStatus.PENDING.name(),
                    null, now, null);
            long id = unblindRequests.insert(row);
            UnblindRequestView body = new UnblindRequestView(
                    id, experimentId, request.participantId(), request.reason(),
                    actor.actorId(), null, UnblindStatus.PENDING.name());
            return new IdempotencyService.BusinessResult(201, body);
        });
    }

    /** 非申请人的 REVIEWER 批准揭盲申请后，申请人才可揭盲。 */
    public WriteOutcome approveUnblind(Actor actor, String experimentId, long unblindRequestId,
                                      ApproveUnblindRequest request) {
        String hash = ParamsHasher.sha256(OperationType.APPROVE_UNBLIND, Map.of(
                "experimentId", experimentId,
                "unblindRequestId", unblindRequestId));
        return idempotency.execute(actor, request.requestId(), OperationType.APPROVE_UNBLIND, hash, () -> {
            UnblindRequestRow unblind = loadRequestInExperiment(experimentId, unblindRequestId);
            if (unblind.applicantId().equals(actor.actorId())) {
                throw new ForbiddenException("unblind request must be approved by another reviewer");
            }
            if (UnblindStatus.APPROVED.name().equals(unblind.status())) {
                throw new ConflictException("unblind request is already approved");
            }
            int updated = unblindRequests.approveIfPending(unblindRequestId, actor.actorId(), Instant.now());
            if (updated == 0) {
                throw new ConflictException("unblind request was approved concurrently");
            }
            UnblindRequestView body = new UnblindRequestView(
                    unblind.id(), experimentId, unblind.participantId(), unblind.reason(),
                    unblind.applicantId(), actor.actorId(), UnblindStatus.APPROVED.name());
            return new IdempotencyService.BusinessResult(200, body);
        });
    }

    /** 仅申请人可查询揭盲结果；其他人 403，未批准 409。实验关闭、退组不撤销已批准揭盲。 */
    public UnblindResultView getUnblindResult(Actor actor, String experimentId, long unblindRequestId) {
        UnblindRequestRow unblind = loadRequestInExperiment(experimentId, unblindRequestId);
        if (!unblind.applicantId().equals(actor.actorId())) {
            throw new ForbiddenException("only the applicant can view the unblind result");
        }
        if (!UnblindStatus.APPROVED.name().equals(unblind.status())) {
            throw new ConflictException("unblind request has not been approved");
        }
        AllocationRow allocation = allocations.findById(unblind.allocationId())
                .orElseThrow(() -> new NotFoundException("allocation not found"));
        SeatRow seat = experiments.findAllSeats(experimentId).stream()
                .filter(s -> s.blockNo() == allocation.blockNo() && s.seatNo() == allocation.seatNo())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("seat mapping not found"));
        return new UnblindResultView(
                experimentId,
                allocation.participantId(),
                allocation.blindCode(),
                seat.treatmentCode(),
                allocation.blockNo(),
                UnblindStatus.APPROVED.name());
    }

    private UnblindRequestRow loadRequestInExperiment(String experimentId, long unblindRequestId) {
        UnblindRequestRow unblind = unblindRequests.findById(unblindRequestId)
                .orElseThrow(() -> new NotFoundException("unblind request not found"));
        if (!unblind.experimentId().equals(experimentId)) {
            throw new NotFoundException("unblind request not found");
        }
        return unblind;
    }

    private String nextUniqueBlindCode() {
        for (int attempt = 0; attempt < BLIND_CODE_ATTEMPTS; attempt++) {
            String candidate = blindCodeGenerator.next();
            if (!allocations.blindCodeExists(candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("could not allocate a unique blind code");
    }
}
