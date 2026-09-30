package com.example.starter.salinity.service;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.CorrectionRequest;
import com.example.starter.salinity.dto.CorrectionView;
import com.example.starter.salinity.dto.SampleView;
import com.example.starter.salinity.dto.SealResult;
import com.example.starter.salinity.dto.SealedProfileView;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.dto.VoyageDataView;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.exception.NotFoundException;
import com.example.starter.salinity.exception.UnprocessableException;
import com.example.starter.salinity.model.AdoptionRecord;
import com.example.starter.salinity.model.Correction;
import com.example.starter.salinity.model.Sample;
import com.example.starter.salinity.model.SealedProfileEntry;
import com.example.starter.salinity.model.Voyage;
import com.example.starter.salinity.model.VoyageStatus;
import com.example.starter.salinity.repository.SalinityRepository;
import com.example.starter.salinity.support.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 盐度采样业务服务：样本提交（双重幂等）、质控替换、航次封存、封存后勘误与查询。
 *
 * <p>并发一致性：质控采纳与封存都先对航次行加 {@code FOR UPDATE} 行锁后再读写采纳状态，
 * 两者在同一航次上完全串行化；封存固化的必为某一时刻一致的采纳版本集合，
 * 封存提交后才到达的采纳请求会因航次已 SEALED 被拒绝。</p>
 */
@Service
public class SalinityService {

    /** 深度允许下限（米）。 */
    public static final int MIN_DEPTH_M = 0;
    /** 深度允许上限（米）。 */
    public static final int MAX_DEPTH_M = 500;
    /** 盐度允许下限（PSU）。 */
    public static final BigDecimal MIN_SALINITY = new BigDecimal("0.000");
    /** 盐度允许上限（PSU）。 */
    public static final BigDecimal MAX_SALINITY = new BigDecimal("45.000");
    /** 封存要求的三个固定深度（米），升序。 */
    public static final List<Integer> SEAL_DEPTHS = List.of(0, 50, 100);

    private final SalinityRepository repository;
    private final BusinessClock clock;

    /**
     * @param repository 仓储
     * @param clock      业务时钟（测试可替换为可控时钟）
     */
    public SalinityService(SalinityRepository repository, BusinessClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 提交结果。
     *
     * @param sample   样本视图
     * @param replayed 是否为同参重放（true 时 HTTP 语义为 200，否则 201）
     */
    public record SubmitOutcome(SampleView sample, boolean replayed) {
    }

    /**
     * 勘误结果。
     *
     * @param correction 勘误视图
     * @param replayed   是否为同参重放
     */
    public record CorrectionOutcome(CorrectionView correction, boolean replayed) {
    }

    /**
     * 提交原始样本：采样编号与 requestId 双重防重复。
     * 同参重放返回原样本；编号相同内容不同返回 409；越界返回 422。
     */
    @Transactional
    public SubmitOutcome submitSample(SubmitSampleRequest request) {
        validateRanges(request.depthM(), request.salinityPsu());

        Optional<Sample> byRequestId = repository.findSampleByRequestId(request.requestId());
        if (byRequestId.isPresent()) {
            Sample existing = byRequestId.get();
            ensureSameContent(existing, request);
            return new SubmitOutcome(SampleView.of(existing), true);
        }
        Optional<Sample> bySampleNo = repository.findSampleBySampleNo(request.sampleNo());
        if (bySampleNo.isPresent()) {
            Sample existing = bySampleNo.get();
            ensureSameContent(existing, request);
            // 同编号同内容但 requestId 不同：视为同一采样的重试，返回原样本。
            return new SubmitOutcome(SampleView.of(existing), true);
        }

        Instant now = clock.now();
        // 航次随首份样本隐式创建；并发首样本由 voyages 主键约束兜底。
        if (repository.findVoyage(request.voyageCode()).isEmpty()) {
            try {
                repository.insertVoyage(new Voyage(request.voyageCode(), VoyageStatus.OPEN, null, now));
            } catch (DuplicateKeyException ignored) {
                // 并发首样本：其他事务已创建航次，继续走加锁分支。
            }
        }
        // 对航次加行锁，串行化同航次并发提交，保证版本号连续且唯一约束不会成为唯一防线；
        // 同时与封存/质控串行，版本号分配读到的是已提交状态。
        repository.lockVoyage(request.voyageCode());
        // 加锁后再次检查幂等键，覆盖等待期间其他事务已提交同参请求的情况。
        Sample afterLock = repository.findSampleByRequestId(request.requestId())
                .or(() -> repository.findSampleBySampleNo(request.sampleNo()))
                .orElse(null);
        if (afterLock != null) {
            ensureSameContent(afterLock, request);
            return new SubmitOutcome(SampleView.of(afterLock), true);
        }
        int versionNo = repository.nextVersionNo(request.voyageCode(), request.station(), request.depthM());
        Sample saved;
        try {
            saved = repository.insertSample(new Sample(
                    null,
                    request.voyageCode(),
                    request.station(),
                    request.depthM(),
                    request.salinityPsu(),
                    request.collectedAtUtc(),
                    request.sampleNo(),
                    request.requestId(),
                    false,
                    versionNo,
                    now));
        } catch (DuplicateKeyException ex) {
            // 跨航次并发复用 requestId/采样编号等情况：重读判定重放或冲突。
            Sample raced = repository.findSampleByRequestId(request.requestId())
                    .or(() -> repository.findSampleBySampleNo(request.sampleNo()))
                    .orElseThrow(() -> ex);
            ensureSameContent(raced, request);
            return new SubmitOutcome(SampleView.of(raced), true);
        }
        return new SubmitOutcome(SampleView.of(saved), false);
    }

    /**
     * 质控采纳：每次将一个版本置为该位置唯一采纳版本；跨航次、封存后采纳返回 422。
     */
    @Transactional
    public SampleView adopt(AdoptRequest request) {
        Optional<AdoptionRecord> previousAdoption =
                repository.findAdoptionByRequestId(request.requestId());
        if (previousAdoption.isPresent()) {
            AdoptionRecord record = previousAdoption.get();
            Sample target = repository.findSampleBySampleNo(request.sampleNo())
                    .orElseThrow(() -> new UnprocessableException("待采纳样本不存在: " + request.sampleNo()));
            if (!Objects.equals(record.sampleId(), target.id())) {
                throw new ConflictException("requestId 已用于采纳其他样本版本");
            }
            return SampleView.of(repository.findSampleById(target.id())
                    .orElseThrow(() -> new UnprocessableException("采纳记录引用的样本不存在")));
        }

        Sample target = repository.findSampleBySampleNo(request.sampleNo())
                .orElseThrow(() -> new UnprocessableException("待采纳样本不存在: " + request.sampleNo()));
        if (!target.voyageCode().equals(request.voyageCode())) {
            throw new UnprocessableException("跨航次质控：样本属于航次 " + target.voyageCode()
                    + "，不能在航次 " + request.voyageCode() + " 中采纳");
        }

        Voyage voyage = repository.lockVoyage(request.voyageCode())
                .orElseThrow(() -> new UnprocessableException("航次不存在: " + request.voyageCode()));
        if (voyage.status() == VoyageStatus.SEALED) {
            throw new UnprocessableException("航次已封存，采纳版本不可替换: " + request.voyageCode());
        }

        // 同一位置只保留一个采纳版本：先清旧再标新；与封存竞争时由航次行锁串行化。
        repository.clearAdoptionForPosition(target.voyageCode(), target.station(), target.depthM());
        repository.markAdopted(target.id());
        repository.insertAdoption(new AdoptionRecord(
                null,
                request.requestId(),
                target.voyageCode(),
                target.station(),
                target.depthM(),
                target.id(),
                clock.now()));

        Sample refreshed = repository.findSampleById(target.id())
                .orElseThrow(() -> new IllegalStateException("采纳后样本丢失"));
        return SampleView.of(refreshed);
    }

    /**
     * 封存航次：要求每个站位的 0/50/100 米均有采纳版本，全部满足才整体固化，否则 422 且不锁定航次。
     */
    @Transactional
    public SealResult seal(String voyageCode) {
        Voyage voyage = repository.lockVoyage(voyageCode)
                .orElseThrow(() -> new NotFoundException("航次不存在: " + voyageCode));
        if (voyage.status() == VoyageStatus.SEALED) {
            // 重复封存是幂等读取：直接返回已固化剖面。
            List<SealedProfileView> frozen = repository.findSealedByVoyage(voyageCode).stream()
                    .map(SealedProfileView::of)
                    .toList();
            return new SealResult(voyageCode, voyage.sealedAtUtc(), frozen);
        }

        List<Sample> allSamples = repository.findSamplesByVoyage(voyageCode);
        List<String> stations = allSamples.stream().map(Sample::station).distinct().sorted().toList();
        if (stations.isEmpty()) {
            throw new UnprocessableException("航次无任何样本，不能封存: " + voyageCode);
        }
        List<Sample> adopted = repository.findAllAdopted(voyageCode);

        // 校验每个站位三个固定深度均有采纳版本；任一缺失即整体失败，不写任何封存数据。
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.Map<SlotKey, Sample> adoptedBySlot = new java.util.HashMap<>();
        for (Sample s : adopted) {
            adoptedBySlot.put(new SlotKey(s.station(), s.depthM()), s);
        }
        for (String station : stations) {
            for (Integer depth : SEAL_DEPTHS) {
                if (!adoptedBySlot.containsKey(new SlotKey(station, depth))) {
                    missing.add("站位 " + station + " 缺 " + depth + " 米采纳样本");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new UnprocessableException("封存失败，剖面不完整: " + String.join("; ", missing));
        }

        Instant sealedAt = clock.now();
        int updated = repository.markSealedIfOpen(voyageCode, sealedAt);
        if (updated == 0) {
            // 并发下已被其他事务封存：读取对方固化结果，保证只采用一个一致集合。
            List<SealedProfileView> frozen = repository.findSealedByVoyage(voyageCode).stream()
                    .map(SealedProfileView::of)
                    .toList();
            Instant sealedAtExisting = repository.findVoyage(voyageCode)
                    .map(Voyage::sealedAtUtc).orElse(null);
            return new SealResult(voyageCode, sealedAtExisting, frozen);
        }

        java.util.List<SealedProfileView> frozenViews = new java.util.ArrayList<>();
        for (String station : stations) {
            for (Integer depth : SEAL_DEPTHS) {
                Sample s = adoptedBySlot.get(new SlotKey(station, depth));
                SealedProfileEntry saved = repository.insertSealedProfile(new SealedProfileEntry(
                        null,
                        voyageCode,
                        station,
                        depth,
                        s.id(),
                        s.sampleNo(),
                        s.salinityPsu(),
                        s.collectedAtUtc(),
                        sealedAt));
                frozenViews.add(SealedProfileView.of(saved));
            }
        }
        return new SealResult(voyageCode, sealedAt, frozenViews);
    }

    /**
     * 封存后追加勘误（只追加，引用原封存条目），原剖面继续可查。
     */
    @Transactional
    public CorrectionOutcome addCorrection(String voyageCode, CorrectionRequest request) {
        if (request.correctedSalinityPsu() != null) {
            validateRanges(request.depthM() == null ? 0 : request.depthM(), request.correctedSalinityPsu());
        }
        Optional<Correction> replayed = repository.findCorrectionByRequestId(request.requestId());
        if (replayed.isPresent()) {
            Correction existing = replayed.get();
            if (!existing.voyageCode().equals(voyageCode)
                    || !existing.station().equals(request.station())
                    || !Objects.equals(existing.depthM(), request.depthM())
                    || !existing.note().equals(request.note())
                    || compareNullableScale(existing.correctedSalinityPsu(),
                            request.correctedSalinityPsu()) != 0) {
                throw new ConflictException("requestId 已用于内容不同的勘误");
            }
            return new CorrectionOutcome(CorrectionView.of(existing), true);
        }

        Voyage voyage = repository.lockVoyage(voyageCode)
                .orElseThrow(() -> new NotFoundException("航次不存在: " + voyageCode));
        if (voyage.status() != VoyageStatus.SEALED) {
            throw new UnprocessableException("航次尚未封存，不能追加勘误: " + voyageCode);
        }
        SealedProfileEntry entry = repository.findSealed(voyageCode, request.station(), request.depthM())
                .orElseThrow(() -> new UnprocessableException(
                        "封存剖面中不存在该站位深度: " + request.station() + " / " + request.depthM() + " 米"));

        Correction saved = repository.insertCorrection(new Correction(
                null,
                voyageCode,
                request.station(),
                request.depthM(),
                entry.id(),
                request.correctedSalinityPsu(),
                request.note(),
                request.requestId(),
                clock.now()));
        return new CorrectionOutcome(CorrectionView.of(saved), false);
    }

    /**
     * 查询航次：原始样本、当前采纳版本、冻结剖面与勘误。
     */
    @Transactional(readOnly = true)
    public VoyageDataView getVoyageData(String voyageCode) {
        Voyage voyage = repository.findVoyage(voyageCode)
                .orElseThrow(() -> new NotFoundException("航次不存在: " + voyageCode));
        List<Sample> all = repository.findSamplesByVoyage(voyageCode);
        List<SealedProfileEntry> sealed = repository.findSealedByVoyage(voyageCode);
        List<Correction> corrections = repository.findCorrectionsByVoyage(voyageCode);
        return new VoyageDataView(
                voyageCode,
                voyage.status() == VoyageStatus.SEALED,
                all.stream().map(SampleView::of).toList(),
                all.stream().filter(Sample::adopted).map(SampleView::of).toList(),
                sealed.stream().map(SealedProfileView::of).toList(),
                corrections.stream().map(CorrectionView::of).toList());
    }

    /**
     * 校验深度与盐度范围（控制层注解之外的服务层防御性校验）。
     */
    private void validateRanges(Integer depthM, BigDecimal salinityPsu) {
        if (depthM == null || depthM < MIN_DEPTH_M || depthM > MAX_DEPTH_M) {
            throw new UnprocessableException("深度越界，允许 0-500 米，实际: " + depthM);
        }
        if (salinityPsu == null
                || salinityPsu.compareTo(MIN_SALINITY) < 0
                || salinityPsu.compareTo(MAX_SALINITY) > 0) {
            throw new UnprocessableException("盐度越界，允许 0-45 PSU，实际: " + salinityPsu);
        }
    }

    /**
     * 重放内容一致性校验：requestId/采样编号命中已存样本时，业务字段必须完全一致，否则 409。
     */
    private void ensureSameContent(Sample existing, SubmitSampleRequest request) {
        boolean same = existing.voyageCode().equals(request.voyageCode())
                && existing.station().equals(request.station())
                && Objects.equals(existing.depthM(), request.depthM())
                && existing.salinityPsu().compareTo(request.salinityPsu()) == 0
                && existing.collectedAtUtc().equals(request.collectedAtUtc())
                && existing.sampleNo().equals(request.sampleNo());
        if (!same) {
            throw new ConflictException("采样编号或 requestId 已存在但提交内容不同: " + request.sampleNo());
        }
    }

    private int compareNullableScale(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || b == null) {
            return 1;
        }
        return a.compareTo(b);
    }

    /**
     * 站位+深度槽位键。
     */
    private record SlotKey(String station, Integer depthM) {
    }
}
