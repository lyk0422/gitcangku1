package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AnonymousPairView;
import com.example.starter.blind.dto.CorrectSampleRequest;
import com.example.starter.blind.dto.LockSamplePairRequest;
import com.example.starter.blind.dto.RegisterSampleRequest;
import com.example.starter.blind.dto.SamplePairView;
import com.example.starter.blind.dto.SampleView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.SamplePairRepository;
import com.example.starter.blind.repo.SamplePairRepository.SamplePairRow;
import com.example.starter.blind.repo.SampleRepository;
import com.example.starter.blind.repo.SampleRepository.SampleRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 盲态样本链业务：
 * 协调员为已分配参与者登记 PRE/POST 合成样本，只保存摘要与采集 UTC 时刻；
 * 更正追加替代记录并引用最近有效样本；锁定要求两份齐全且 POST 晚于 PRE，
 * 退组参与者不能新锁定；锁定后禁止更正，冻结当时摘要；试验关闭不撤销已锁定对。
 * 更正与锁定都先锁定分配行，保证与退组、彼此之间的竞争只形成完整一致的配对。
 */
@Service
public class SampleService {

    static final String PHASE_PRE = "PRE";
    static final String PHASE_POST = "POST";

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final SampleRepository sampleRepository;
    private final SamplePairRepository samplePairRepository;
    private final BlindCodeGenerator codeGenerator;
    private final Clock clock;

    public SampleService(ExperimentRepository experimentRepository,
                         AllocationRepository allocationRepository,
                         SampleRepository sampleRepository,
                         SamplePairRepository samplePairRepository,
                         BlindCodeGenerator codeGenerator,
                         Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.sampleRepository = sampleRepository;
        this.samplePairRepository = samplePairRepository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /**
     * 登记某阶段首份合成样本；允许先上传 POST 形成待配对状态。
     */
    @Transactional
    public SampleView register(String experimentId, String participantId,
                               RegisterSampleRequest request, String actorId) {
        mustFindAllocation(experimentId, participantId);
        if (sampleRepository.findActive(experimentId, participantId, request.phase()) != null) {
            throw ApiException.conflict("该阶段已存在有效样本，请通过更正追加替代记录");
        }
        SampleRow row = new SampleRow(0L, experimentId, participantId, request.phase(),
                request.sampleCode(), request.digest(), request.collectedAt(), 1, null,
                "ACTIVE", actorId, clock.nowMillis());
        try {
            sampleRepository.insert(row, activeSlot(experimentId, participantId, request.phase()));
        } catch (DuplicateKeyException e) {
            if (sampleRepository.isDuplicateSampleCode(e)) {
                throw ApiException.conflict("样本编号已被使用，不得用于不同参与者或不同阶段");
            }
            if (sampleRepository.isDuplicateActiveSlot(e)) {
                throw ApiException.conflict("该阶段已存在有效样本，请通过更正追加替代记录");
            }
            throw e;
        }
        return toView(sampleRepository.findActive(experimentId, participantId, request.phase()));
    }

    /**
     * 更正：追加替代记录并引用最近有效样本；锁定后禁止更正。
     */
    @Transactional
    public SampleView correct(String experimentId, String participantId,
                              CorrectSampleRequest request, String actorId) {
        // 与锁定、退组串行化：先锁定分配行再判断配对状态
        AllocationRow allocation = lockAllocation(experimentId, participantId);
        if (samplePairRepository.findByParticipant(experimentId, participantId) != null) {
            throw ApiException.conflict("样本对已锁定，禁止更正");
        }
        SampleRow current = sampleRepository.lockActive(experimentId, participantId, request.phase());
        if (current == null) {
            throw ApiException.conflict("该阶段无有效样本可更正，请先登记");
        }
        if (sampleRepository.markSuperseded(current.id()) == 0) {
            throw ApiException.conflict("样本已被并发更正，请重试");
        }
        SampleRow row = new SampleRow(0L, experimentId, participantId, request.phase(),
                request.sampleCode(), request.digest(), request.collectedAt(),
                current.chainVersion() + 1, current.id(), "ACTIVE", actorId, clock.nowMillis());
        try {
            sampleRepository.insert(row, activeSlot(experimentId, participantId, request.phase()));
        } catch (DuplicateKeyException e) {
            if (sampleRepository.isDuplicateSampleCode(e)) {
                throw ApiException.conflict("样本编号已被使用，不得用于不同参与者或不同阶段");
            }
            throw e;
        }
        return toView(sampleRepository.findActive(experimentId, participantId, request.phase()));
    }

    /**
     * 锁定配对：两份样本齐全且 POST 晚于 PRE；退组参与者不能新锁定；
     * 期望版本提供时必须与当前有效链版本一致。
     */
    @Transactional
    public SamplePairView lock(String experimentId, String participantId,
                               LockSamplePairRequest request, String actorId) {
        AllocationRow allocation = lockAllocation(experimentId, participantId);
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，不能新锁定样本配对");
        }
        if (samplePairRepository.findByParticipant(experimentId, participantId) != null) {
            throw ApiException.conflict("样本配对已锁定");
        }
        SampleRow pre = sampleRepository.lockActive(experimentId, participantId, PHASE_PRE);
        SampleRow post = sampleRepository.lockActive(experimentId, participantId, PHASE_POST);
        if (pre == null || post == null) {
            throw ApiException.conflict("PRE 与 POST 两份样本齐全后才能锁定");
        }
        if (request.expectedPreVersion() != null
                && request.expectedPreVersion() != pre.chainVersion()) {
            throw ApiException.conflict("PRE 样本版本已变化，请刷新后重试");
        }
        if (request.expectedPostVersion() != null
                && request.expectedPostVersion() != post.chainVersion()) {
            throw ApiException.conflict("POST 样本版本已变化，请刷新后重试");
        }
        if (post.collectedAt() <= pre.collectedAt()) {
            throw ApiException.full("POST 采集时刻必须晚于 PRE，请先更正样本");
        }
        long now = clock.nowMillis();
        SamplePairRow inserted = insertWithUniquePairCode(experimentId, participantId,
                pre, post, actorId, now);
        return new SamplePairView(inserted.pairCode(), experimentId, participantId,
                inserted.preDigest(), inserted.postDigest(),
                inserted.preCollectedAt(), inserted.postCollectedAt(), inserted.lockedAt());
    }

    private SamplePairRow insertWithUniquePairCode(String experimentId, String participantId,
                                                   SampleRow pre, SampleRow post,
                                                   String actorId, long now) {
        // 配对码随机冲突概率极低，仍由唯一索引兜底并重试
        for (int attempt = 0; attempt < 5; attempt++) {
            SamplePairRow row = new SamplePairRow(0L, codeGenerator.nextCode(),
                    experimentId, participantId, pre.id(), post.id(),
                    pre.digest(), post.digest(), pre.collectedAt(), post.collectedAt(),
                    actorId, now);
            try {
                samplePairRepository.insert(row);
                return samplePairRepository.findByParticipant(experimentId, participantId);
            } catch (DuplicateKeyException e) {
                if (samplePairRepository.isDuplicatePairCode(e)) {
                    continue;
                }
                if (samplePairRepository.isDuplicateParticipant(e)) {
                    throw ApiException.conflict("样本配对已锁定");
                }
                throw e;
            }
        }
        throw ApiException.conflict("配对码生成冲突，请重试");
    }

    /**
     * 协调员查询自己提交的样本记录（含被替代历史）。
     */
    public List<SampleView> listOwnSamples(String experimentId, String participantId, String actorId) {
        mustFindExperiment(experimentId);
        return sampleRepository.findByParticipantAndActor(experimentId, participantId, actorId)
                .stream().map(this::toView).toList();
    }

    /**
     * 分析查询：只返回配对码与两份冻结摘要，不含参与者编号、盲码或处理代码。
     */
    public List<AnonymousPairView> listAnonymousPairs(String experimentId) {
        mustFindExperiment(experimentId);
        return samplePairRepository.findAnonymousByExperiment(experimentId).stream()
                .map(p -> new AnonymousPairView(p.pairCode(), p.preDigest(), p.postDigest()))
                .toList();
    }

    private AllocationRow mustFindAllocation(String experimentId, String participantId) {
        mustFindExperiment(experimentId);
        AllocationRow row =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (row == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        return row;
    }

    private AllocationRow lockAllocation(String experimentId, String participantId) {
        mustFindExperiment(experimentId);
        AllocationRow row =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (row == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        return row;
    }

    private void mustFindExperiment(String experimentId) {
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    /**
     * 有效占位键：长度前缀编码，避免参与者编号含分隔符时不同键发生碰撞。
     */
    static String activeSlot(String experimentId, String participantId, String phase) {
        return experimentId.length() + ":" + experimentId
                + participantId.length() + ":" + participantId + phase;
    }

    private SampleView toView(SampleRow row) {
        return new SampleView(row.id(), row.experimentId(), row.participantId(), row.phase(),
                row.sampleCode(), row.digest(), row.collectedAt(), row.chainVersion(),
                row.supersedesId(), row.status(), row.submittedActor(), row.createdAt());
    }
}