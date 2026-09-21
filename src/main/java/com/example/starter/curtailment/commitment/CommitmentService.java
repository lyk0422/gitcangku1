package com.example.starter.curtailment.commitment;

import com.example.starter.curtailment.common.Check;
import com.example.starter.curtailment.dispatch.DispatchRepository;
import com.example.starter.curtailment.error.ApiException;
import com.example.starter.curtailment.idempotency.IdempotencyExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 容量承诺业务：创建（区间不重叠）、暂停（存在重叠已发布调度时拒绝）、查询。
 */
@Service
public class CommitmentService {

    private final CommitmentRepository commitmentRepository;
    private final DispatchRepository dispatchRepository;
    private final IdempotencyExecutor idempotency;

    public CommitmentService(CommitmentRepository commitmentRepository, DispatchRepository dispatchRepository,
                             IdempotencyExecutor idempotency) {
        this.commitmentRepository = commitmentRepository;
        this.dispatchRepository = dispatchRepository;
        this.idempotency = idempotency;
    }

    @Transactional
    public CommitmentResponse create(CreateCommitmentRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");
        String commitmentKey = Check.requireText(request.commitmentKey(), "commitmentKey");
        String siteId = Check.requireText(request.siteId(), "siteId");
        Check.requireInterval(request.validFrom(), request.validTo(), "validFrom", "validTo");
        BigDecimal maxPower = Check.parsePower(request.maxPowerKw(), "maxPowerKw", true);

        String fingerprint = "commitmentKey=" + commitmentKey
                + "|siteId=" + siteId
                + "|validFrom=" + request.validFrom()
                + "|validTo=" + request.validTo()
                + "|maxPowerKw=" + maxPower.stripTrailingZeros().toPlainString();

        return idempotency.execute(commandKey, "CREATE_COMMITMENT", fingerprint, CommitmentResponse.class, () -> {
            if (commitmentRepository.countOverlapping(siteId, request.validFrom(), request.validTo()) > 0) {
                throw ApiException.conflict("同一站点的有效承诺区间不得重叠");
            }
            Instant now = Instant.now();
            Commitment toSave = new Commitment(0L, commitmentKey, siteId, request.validFrom(), request.validTo(),
                    maxPower, CommitmentStatus.ACTIVE, now, now);
            Commitment saved;
            try {
                saved = commitmentRepository.insert(toSave);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("commitmentKey 已存在");
            }
            return toResponse(saved);
        });
    }

    @Transactional
    public CommitmentResponse suspend(String commitmentKey, SuspendCommitmentRequest request) {
        String commandKey = Check.requireText(request.commandKey(), "commandKey");
        String fingerprint = "commitmentKey=" + commitmentKey;

        return idempotency.execute(commandKey, "SUSPEND_COMMITMENT", fingerprint, CommitmentResponse.class, () -> {
            Commitment commitment = commitmentRepository.findByKeyForUpdate(commitmentKey)
                    .orElseThrow(() -> ApiException.notFound("承诺不存在"));
            if (commitment.status() == CommitmentStatus.SUSPENDED) {
                throw ApiException.conflict("承诺已处于暂停状态");
            }
            if (dispatchRepository.existsPublishedOverlapOnSite(commitment.siteId(), commitment.validFrom(),
                    commitment.validTo())) {
                throw ApiException.conflict("存在落在承诺区间内的已发布调度，不能暂停");
            }
            Instant now = Instant.now();
            commitmentRepository.updateStatus(commitment.id(), CommitmentStatus.SUSPENDED, now);
            return toResponse(new Commitment(commitment.id(), commitment.commitmentKey(), commitment.siteId(),
                    commitment.validFrom(), commitment.validTo(), commitment.maxPowerKw(),
                    CommitmentStatus.SUSPENDED, commitment.createdAt(), now));
        });
    }

    @Transactional(readOnly = true)
    public CommitmentResponse get(String commitmentKey) {
        Commitment commitment = commitmentRepository.findByKey(commitmentKey)
                .orElseThrow(() -> ApiException.notFound("承诺不存在"));
        return toResponse(commitment);
    }

    private CommitmentResponse toResponse(Commitment commitment) {
        return new CommitmentResponse(commitment.commitmentKey(), commitment.siteId(), commitment.validFrom(),
                commitment.validTo(), Check.formatPower(commitment.maxPowerKw()), commitment.status().name(),
                commitment.createdAt());
    }
}
