package com.example.starter.blind;

import com.example.starter.blind.dto.AssignmentView;
import com.example.starter.blind.dto.AssignRequest;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 盲法实验业务服务：权限校验先于幂等回放；处理代码只在揭盲结果中返回。
 */
@Service
public class ExperimentService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String SEAT_ASSIGNED = "ASSIGNED";
    private static final String SEAT_WITHDRAWN = "WITHDRAWN";
    private static final String UNBLIND_APPROVED = "APPROVED";
    private static final char[] BLIND_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final ExperimentRepository repository;
    private final IdempotencyService idempotency;
    private final SecureRandom random = new SecureRandom();

    public ExperimentService(ExperimentRepository repository, IdempotencyService idempotency) {
        this.repository = repository;
        this.idempotency = idempotency;
    }

    /** 创建实验：仅协调员；固定 2~8 个区组，每组 4 席且恰好两个 A 两个 B。 */
    public ExperimentView createExperiment(String actorId, ActorRole role, CreateExperimentRequest req) {
        requireRole(role, ActorRole.COORDINATOR);
        validateBlocks(req.blocks());
        String fingerprint = "create|" + req.experimentId() + '|' + req.blocks();
        return idempotency.execute(req.requestId(), actorId, role, "CREATE_EXPERIMENT",
                fingerprint, ExperimentView.class, () -> {
                    try {
                        repository.insertExperiment(req.experimentId(), req.blocks().size(),
                                LocalDateTime.now());
                    } catch (DuplicateKeyException dup) {
                        throw ApiException.conflict("experiment already exists: " + req.experimentId());
                    }
                    for (int b = 0; b < req.blocks().size(); b++) {
                        List<String> seats = req.blocks().get(b);
                        for (int s = 0; s < seats.size(); s++) {
                            repository.insertSeat(req.experimentId(), b + 1, s + 1, seats.get(s));
                        }
                    }
                    return new ExperimentView(req.experimentId(), req.blocks().size(), STATUS_OPEN);
                });
    }

    /** 参与者首次登记：原子领取按区组、席位顺序的第一个空位；满额 422，关闭 409。 */
    public AssignmentView assign(String actorId, ActorRole role, String experimentId, AssignRequest req) {
        requireRole(role, ActorRole.COORDINATOR);
        String fingerprint = "assign|" + experimentId + '|' + req.participantId();
        return idempotency.execute(req.requestId(), actorId, role, "ASSIGN",
                fingerprint, AssignmentView.class, () -> {
                    ExperimentRepository.ExperimentRow exp = requireExperiment(experimentId);
                    if (!STATUS_OPEN.equals(exp.status())) {
                        throw ApiException.conflict("experiment is closed: " + experimentId);
                    }
                    if (repository.findSeatByParticipant(experimentId, req.participantId()).isPresent()) {
                        throw ApiException.conflict(
                                "participant already assigned: " + req.participantId());
                    }
                    String blindCode = newBlindCode();
                    for (ExperimentRepository.SeatRow seat : repository.findEmptySeats(experimentId)) {
                        try {
                            if (repository.claimSeat(experimentId, seat.blockNo(), seat.seatNo(),
                                    req.participantId(), blindCode, LocalDateTime.now()) == 1) {
                                return new AssignmentView(experimentId, req.participantId(),
                                        blindCode, seat.blockNo(), SEAT_ASSIGNED);
                            }
                        } catch (DuplicateKeyException dup) {
                            throw ApiException.conflict(
                                    "participant already assigned: " + req.participantId());
                        }
                    }
                    throw ApiException.unprocessable("experiment is full: " + experimentId);
                });
    }

    /** 退组：不释放席位、不重排已有分配。 */
    public AssignmentView withdraw(String actorId, ActorRole role, String experimentId,
                                   String participantId, String requestId) {
        requireRole(role, ActorRole.COORDINATOR);
        String fingerprint = "withdraw|" + experimentId + '|' + participantId;
        return idempotency.execute(requestId, actorId, role, "WITHDRAW",
                fingerprint, AssignmentView.class, () -> {
                    requireExperiment(experimentId);
                    ExperimentRepository.SeatRow seat = repository
                            .findSeatByParticipant(experimentId, participantId)
                            .orElseThrow(() -> ApiException.notFound(
                                    "participant not assigned: " + participantId));
                    if (SEAT_WITHDRAWN.equals(seat.seatStatus())) {
                        throw ApiException.conflict("participant already withdrawn: " + participantId);
                    }
                    repository.withdrawSeat(experimentId, participantId, LocalDateTime.now());
                    return new AssignmentView(experimentId, participantId, seat.blindCode(),
                            seat.blockNo(), SEAT_WITHDRAWN);
                });
    }

    /** 关闭实验：关闭后拒绝新增分配。 */
    public ExperimentView close(String actorId, ActorRole role, String experimentId, String requestId) {
        requireRole(role, ActorRole.COORDINATOR);
        String fingerprint = "close|" + experimentId;
        return idempotency.execute(requestId, actorId, role, "CLOSE",
                fingerprint, ExperimentView.class, () -> {
                    ExperimentRepository.ExperimentRow exp = requireExperiment(experimentId);
                    if (repository.updateExperimentStatus(experimentId, STATUS_OPEN, STATUS_CLOSED) == 0) {
                        throw ApiException.conflict("experiment already closed: " + experimentId);
                    }
                    return new ExperimentView(exp.experimentId(), exp.blockCount(), STATUS_CLOSED);
                });
    }

    /** 普通查询：只返回盲码、区组号和参与者编号，含退组状态。 */
    public AssignmentView getAssignment(String experimentId, String participantId) {
        requireExperiment(experimentId);
        ExperimentRepository.SeatRow seat = repository
                .findSeatByParticipant(experimentId, participantId)
                .orElseThrow(() -> ApiException.notFound("participant not assigned: " + participantId));
        return new AssignmentView(experimentId, participantId, seat.blindCode(),
                seat.blockNo(), seat.seatStatus());
    }

    /** 协调员为已分配参与者提出带原因的揭盲申请；同一分配至多一个待审申请。 */
    public UnblindRequestView applyUnblind(String actorId, ActorRole role, String experimentId,
                                           UnblindApplyRequest req) {
        requireRole(role, ActorRole.COORDINATOR);
        String fingerprint = "unblind-apply|" + experimentId + '|' + req.participantId()
                + '|' + req.reason();
        return idempotency.execute(req.requestId(), actorId, role, "UNBLIND_APPLY",
                fingerprint, UnblindRequestView.class, () -> {
                    requireExperiment(experimentId);
                    // 锁定实验行，串行化同一实验的揭盲申请，保证“同一分配至多一个待审申请”
                    repository.lockExperiment(experimentId);
                    ExperimentRepository.SeatRow seat = repository
                            .findSeatByParticipant(experimentId, req.participantId())
                            .orElseThrow(() -> ApiException.notFound(
                                    "participant not assigned: " + req.participantId()));
                    if (repository.countPendingUnblind(experimentId, seat.blockNo(), seat.seatNo()) > 0) {
                        throw ApiException.conflict(
                                "pending unblind request exists for participant: " + req.participantId());
                    }
                    String unblindId = "UB-" + UUID.randomUUID();
                    repository.insertUnblindRequest(new ExperimentRepository.UnblindRow(
                            unblindId, experimentId, seat.blockNo(), seat.seatNo(),
                            req.participantId(), actorId, req.reason(), "PENDING", null),
                            LocalDateTime.now());
                    return new UnblindRequestView(unblindId, experimentId, req.participantId(),
                            actorId, "PENDING");
                });
    }

    /** 另一名审核员批准揭盲申请。 */
    public UnblindRequestView approveUnblind(String actorId, ActorRole role, String experimentId,
                                             String unblindId, String requestId) {
        requireRole(role, ActorRole.REVIEWER);
        String fingerprint = "unblind-approve|" + experimentId + '|' + unblindId;
        return idempotency.execute(requestId, actorId, role, "UNBLIND_APPROVE",
                fingerprint, UnblindRequestView.class, () -> {
                    requireExperiment(experimentId);
                    ExperimentRepository.UnblindRow ub = repository
                            .findUnblind(experimentId, unblindId)
                            .orElseThrow(() -> ApiException.notFound(
                                    "unblind request not found: " + unblindId));
                    if (ub.applicantId().equals(actorId)) {
                        throw ApiException.forbidden("approver must differ from applicant");
                    }
                    if (repository.approveUnblind(unblindId, actorId, LocalDateTime.now()) == 0) {
                        throw ApiException.conflict("unblind request already approved: " + unblindId);
                    }
                    return new UnblindRequestView(unblindId, experimentId, ub.participantId(),
                            ub.applicantId(), UNBLIND_APPROVED);
                });
    }

    /** 揭盲结果：仅申请人可查，其他人 403，未批准 409；关闭、退组不撤销已批准的揭盲。 */
    public UnblindResultView getUnblindResult(String actorId, String experimentId, String unblindId) {
        requireExperiment(experimentId);
        ExperimentRepository.UnblindRow ub = repository.findUnblind(experimentId, unblindId)
                .orElseThrow(() -> ApiException.notFound("unblind request not found: " + unblindId));
        if (!ub.applicantId().equals(actorId)) {
            throw ApiException.forbidden("only the applicant may view the unblind result");
        }
        if (!UNBLIND_APPROVED.equals(ub.status())) {
            throw ApiException.conflict("unblind request not approved: " + unblindId);
        }
        ExperimentRepository.SeatRow seat = repository
                .findSeat(experimentId, ub.blockNo(), ub.seatNo())
                .orElseThrow(() -> ApiException.notFound("seat not found for unblind request"));
        return new UnblindResultView(unblindId, experimentId, ub.participantId(),
                UNBLIND_APPROVED, seat.treatmentCode());
    }

    private ExperimentRepository.ExperimentRow requireExperiment(String experimentId) {
        return repository.findExperiment(experimentId)
                .orElseThrow(() -> ApiException.notFound("experiment not found: " + experimentId));
    }

    private static void requireRole(ActorRole actual, ActorRole required) {
        if (actual != required) {
            throw ApiException.forbidden("role " + required + " required");
        }
    }

    private static void validateBlocks(List<List<String>> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            List<String> seats = blocks.get(i);
            long a = seats.stream().filter("A"::equals).count();
            long b = seats.stream().filter("B"::equals).count();
            if (a != 2 || b != 2) {
                throw ApiException.badRequest(
                        "block " + (i + 1) + " must contain exactly two A and two B treatment codes");
            }
        }
    }

    private String newBlindCode() {
        StringBuilder code = new StringBuilder("BLD-");
        for (int i = 0; i < 12; i++) {
            code.append(BLIND_ALPHABET[random.nextInt(BLIND_ALPHABET.length)]);
        }
        return code.toString();
    }
}
