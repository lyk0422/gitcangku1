package com.example.starter.evidence.aliquot;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.SampleKind;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.aliquot.dto.SamplingApplyRequest;
import com.example.starter.evidence.aliquot.dto.SamplingItemInput;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合取样申请事务执行器：在单一事务内先插入申请单（request_id/aliquot_key 唯一约束立即生效），
 * 再逐母样成对锁定并条件预留，最后写明细与命令日志。
 * 任一步失败（余额不足、母样状态非法、键冲突）整事务回滚：无预留、申请单与命令均不占键。
 */
@Component
public class SamplingApplyExecutor {

    private final SamplingOrderRepository samplingOrderRepository;
    private final SamplingItemRepository samplingItemRepository;
    private final MotherSampleRepository motherSampleRepository;
    private final EvidenceRepository evidenceRepository;

    public SamplingApplyExecutor(SamplingOrderRepository samplingOrderRepository,
                                 SamplingItemRepository samplingItemRepository,
                                 MotherSampleRepository motherSampleRepository,
                                 EvidenceRepository evidenceRepository) {
        this.samplingOrderRepository = samplingOrderRepository;
        this.samplingItemRepository = samplingItemRepository;
        this.motherSampleRepository = motherSampleRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 预留完成后由服务层构建视图并写入命令日志（仍处于本事务内）。
     */
    @FunctionalInterface
    interface Recorder {
        StoredResponse record(String actorId, String requestHash);
    }

    /**
     * 执行申请事务。items 必须已去重并按母样键排序。
     * request_id/aliquot_key/command_key 唯一冲突以 {@link DuplicateKeyException} 抛出，
     * 由非事务的服务层捕获后重放先提交事务的首次结果。
     */
    @Transactional
    public StoredResponse execute(String actorId, SamplingApplyRequest request,
                                  List<SamplingItemInput> items, String fingerprint,
                                  String requestHash, Recorder recorder) {
        // aliquotKey 不得与既有证物键冲突（与 sampling_order 的唯一约束分属不同表）。
        if (evidenceRepository.findByKey(request.aliquotKey()).isPresent()) {
            throw ApiException.conflict("aliquotKey 与既有证物键冲突: " + request.aliquotKey());
        }
        LocalDateTime now = LocalDateTime.now();
        // 先落申请单：并发同 requestId/aliquotKey 在此被唯一约束串行化，败者回滚不占键。
        samplingOrderRepository.insert(request.requestId(), request.aliquotKey(), actorId,
                fingerprint, request.commandKey(), now);
        for (SamplingItemInput item : items) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(item.sampleKey())
                    .orElseThrow(() -> ApiException.notFound("母样证物不存在: " + item.sampleKey()));
            requireUsableMotherEvidence(evidence, actorId, item.sampleKey());
            motherSampleRepository.findByKeyForUpdate(item.sampleKey())
                    .orElseThrow(() -> ApiException.notFound("母样未登记总量: " + item.sampleKey()));
            if (!motherSampleRepository.reserve(item.sampleKey(), item.qty(), now)) {
                throw ApiException.unprocessable(
                        "母样可用余额不足，整单预留失败: " + item.sampleKey());
            }
        }
        for (SamplingItemInput item : items) {
            MotherSample mother = motherSampleRepository.findByKey(item.sampleKey()).orElseThrow();
            Evidence evidence = evidenceRepository.findByKey(item.sampleKey()).orElseThrow();
            samplingItemRepository.insert(request.requestId(), item.sampleKey(), item.qty(),
                    mother.unit(), mother.version(), evidence.version(), actorId,
                    evidence.status().name(), now);
        }
        return recorder.record(actorId, requestHash);
    }

    private void requireUsableMotherEvidence(Evidence evidence, String actorId, String sampleKey) {
        if (evidence.sampleKind() == SampleKind.ALIQUOT) {
            throw ApiException.unprocessable("联合取样生成的子样不可再取样: " + sampleKey);
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是母样当前保管人: " + sampleKey);
        }
        switch (evidence.status()) {
            case SEAL_BROKEN -> throw ApiException.unprocessable("母样封条异常，禁止取样: " + sampleKey);
            case BORROWED -> throw ApiException.conflict("母样已借出，禁止取样: " + sampleKey);
            case TRANSFER_PENDING -> throw ApiException.conflict("母样存在待接收交接，禁止取样: " + sampleKey);
            default -> {
            }
        }
    }
}
