package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AnalysisPairView;
import com.example.starter.blind.dto.SampleChainView;
import com.example.starter.blind.dto.SampleLockView;
import com.example.starter.blind.dto.SampleRecordView;
import com.example.starter.blind.dto.SampleRegisterRequest;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.SampleRepository;
import com.example.starter.blind.repo.SampleRepository.PairLockRow;
import com.example.starter.blind.repo.SampleRepository.SampleRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 盲态样本链核心：协调员为已分配参与者登记 PRE/POST 合成样本（只存摘要与采集 UTC 时刻），
 * 更正追加替代记录并引用最近有效样本；两份齐全且时序合法（POST 晚于 PRE）后按期望版本锁定，
 * 锁定后禁止更正；分析人仅见配对码与两份冻结摘要。
 * 更正、退组与锁定通过分配行级锁串行化，竞争只会形成完整一致的配对。
 */
@Service
public class SampleService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final SampleRepository sampleRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public SampleService(ExperimentRepository experimentRepository,
                         AllocationRepository allocationRepository,
                         SampleRepository sampleRepository,
                         BlindCodeGenerator blindCodeGenerator,
                         Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.sampleRepository = sampleRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 登记或更正样本：同阶段已有有效记录时追加替代记录并引用最近有效样本。
     * 已锁定的参与者禁止更正；样本编号全实验唯一，失败回滚不占编号。
     */
    @Transactional
    public SampleRecordView registerSample(String experimentId, String participantId,
                                           SampleRegisterRequest request, String actorId) {
        mustFindExperiment(experimentId);
        AllocationRow allocation = lockAllocation(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if (sampleRepository.findLock(experimentId, participantId) != null) {
            throw ApiException.conflict("配对已锁定，禁止更正样本");
        }
        SampleRow previous = sampleRepository.findActiveSample(
                experimentId, participantId, request.phase());
        Long supersedesId = null;
        if (previous != null) {
            sampleRepository.markSuperseded(previous.id());
            supersedesId = previous.id();
        }
        SampleRow row = new SampleRow(0L, experimentId, participantId, request.phase(),
                request.sampleNo(), request.digest(), request.collectedAt(), supersedesId,
                "ACTIVE", actorId, clock.nowMillis());
        try {
            sampleRepository.insertSample(row);
        } catch (DuplicateKeyException e) {
            if (sampleRepository.isDuplicateSampleNo(e)) {
                // 样本编号不得同时用于不同参与者或不同阶段；事务回滚，更正不生效。
                throw ApiException.conflict("样本编号已被使用: " + request.sampleNo());
            }
            throw e;
        }
        SampleRow inserted = sampleRepository.findActiveSample(
                experimentId, participantId, request.phase());
        return toRecordView(inserted, previous == null ? null : previous.sampleNo());
    }

    /**
     * 锁定配对：要求两份齐全、POST 晚于 PRE、期望版本匹配、参与者在组且未锁定。
     * 锁定后冻结当时摘要；试验关闭不撤销已锁定对。
     */
    @Transactional
    public SampleLockView lockPair(String experimentId, String participantId,
                                   int expectedVersion, String actorId) {
        mustFindExperiment(experimentId);
        AllocationRow allocation = lockAllocation(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，不能新锁定配对");
        }
        if (sampleRepository.findLock(experimentId, participantId) != null) {
            throw ApiException.conflict("配对已锁定");
        }
        int version = sampleRepository.chainVersion(experimentId, participantId);
        if (expectedVersion != version) {
            throw ApiException.conflict("期望版本与当前样本链版本不一致");
        }
        SampleRow pre = sampleRepository.findActiveSample(experimentId, participantId, "PRE");
        SampleRow post = sampleRepository.findActiveSample(experimentId, participantId, "POST");
        if (pre == null || post == null) {
            throw ApiException.unprocessable("PRE 与 POST 样本未齐全，不能锁定");
        }
        if (post.collectedAt() <= pre.collectedAt()) {
            throw ApiException.unprocessable("POST 采集时刻必须晚于 PRE");
        }
        long now = clock.nowMillis();
        PairLockRow lock = insertWithUniquePairCode(experimentId, participantId, pre, post,
                version, actorId, now);
        return new SampleLockView(lock.experimentId(), lock.participantId(), lock.pairCode(),
                lock.preDigest(), lock.postDigest(), lock.version(), lock.lockedAt());
    }

    /**
     * 协调员查询本人提交的样本链；无本人记录时 404，不泄露他人提交的存在性。
     */
    public SampleChainView getOwnChain(String experimentId, String participantId, String actorId) {
        mustFindExperiment(experimentId);
        List<SampleRow> chain = sampleRepository.findChain(experimentId, participantId);
        List<SampleRow> own = chain.stream().filter(r -> r.actor().equals(actorId)).toList();
        if (own.isEmpty()) {
            throw ApiException.notFound("无该参与者的样本记录");
        }
        PairLockRow lock = sampleRepository.findLock(experimentId, participantId);
        List<SampleRecordView> records = own.stream()
                .map(r -> toRecordView(r, supersedesSampleNo(chain, r)))
                .toList();
        return new SampleChainView(experimentId, participantId,
                sampleRepository.chainVersion(experimentId, participantId),
                lock != null, lock == null ? null : lock.pairCode(), records);
    }

    /**
     * 分析人查询实验内全部已锁定匿名配对：只有配对码与两份冻结摘要。
     */
    public List<AnalysisPairView> listLockedPairs(String experimentId) {
        mustFindExperiment(experimentId);
        return sampleRepository.findLocksByExperiment(experimentId).stream()
                .map(lock -> new AnalysisPairView(lock.pairCode(), lock.preDigest(),
                        lock.postDigest()))
                .toList();
    }

    private PairLockRow insertWithUniquePairCode(String experimentId, String participantId,
                                                 SampleRow pre, SampleRow post, int version,
                                                 String actorId, long now) {
        // 配对码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            PairLockRow row = new PairLockRow(0L, experimentId, participantId,
                    blindCodeGenerator.nextCode(), pre.id(), post.id(), pre.digest(),
                    post.digest(), pre.collectedAt(), post.collectedAt(), version, actorId, now);
            try {
                sampleRepository.insertLock(row);
                return sampleRepository.findLock(experimentId, participantId);
            } catch (DuplicateKeyException e) {
                if (sampleRepository.isDuplicatePairCode(e)) {
                    continue;
                }
                if (sampleRepository.isDuplicatePairLock(e)) {
                    // 并发锁定由唯一约束兜底，事务随幂等占位一并回滚，不泄露唯一键异常。
                    throw ApiException.conflict("配对已锁定，请重试");
                }
                throw e;
            }
        }
        throw ApiException.conflict("配对码生成冲突，请重试");
    }

    private AllocationRow lockAllocation(String experimentId, String participantId) {
        return allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
    }

    private void mustFindExperiment(String experimentId) {
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private String supersedesSampleNo(List<SampleRow> chain, SampleRow row) {
        if (row.supersedesId() == null) {
            return null;
        }
        return chain.stream()
                .filter(r -> r.id() == row.supersedesId())
                .map(SampleRow::sampleNo)
                .findFirst()
                .orElse(null);
    }

    private SampleRecordView toRecordView(SampleRow row, String supersedesSampleNo) {
        return new SampleRecordView(row.sampleNo(), row.phase(), row.digest(), row.collectedAt(),
                row.status(), supersedesSampleNo, row.createdAt());
    }
}