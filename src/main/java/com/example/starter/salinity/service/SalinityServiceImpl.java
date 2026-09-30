package com.example.starter.salinity.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.salinity.command.AddErratumCommand;
import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.command.SubmitSampleCommand;
import com.example.starter.salinity.exception.BusinessRuleException;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.exception.ResourceNotFoundException;
import com.example.starter.salinity.po.ProfileErratum;
import com.example.starter.salinity.po.SalinitySample;
import com.example.starter.salinity.po.SealedProfilePoint;
import com.example.starter.salinity.po.Voyage;
import com.example.starter.salinity.po.VoyageStatus;
import com.example.starter.salinity.repo.SalinityRepository;
import com.example.starter.salinity.view.AdoptedSampleView;
import com.example.starter.salinity.view.ErratumView;
import com.example.starter.salinity.view.FrozenProfilePointView;
import com.example.starter.salinity.view.SampleView;
import com.example.starter.salinity.view.VoyageDataView;

/**
 * 盐度采样业务实现。
 *
 * <p>并发与一致性约定：</p>
 * <ul>
 *     <li>采纳与封存事务都先对航次行 {@code FOR UPDATE} 加锁，二者完全串行；</li>
 *     <li>采纳额外锁定站位深度样本行，封存对全部采纳行加锁，双重保险防止版本集合混入；</li>
 *     <li>封存校验失败整体回滚，航次保持 OPEN，不锁定后续操作。</li>
 * </ul>
 */
@Service
public class SalinityServiceImpl implements SalinityService {

    /** 深度允许范围（米，含边界）。 */
    static final int MIN_DEPTH_M = 0;
    static final int MAX_DEPTH_M = 500;

    /** 盐度允许范围（PSU，含边界）。 */
    static final BigDecimal MIN_SALINITY = BigDecimal.ZERO;
    static final BigDecimal MAX_SALINITY = new BigDecimal("45");

    /** 封存要求的标准层深度（米），升序。 */
    static final List<Integer> REQUIRED_DEPTHS = List.of(0, 50, 100);

    private final SalinityRepository repository;
    private final Clock clock;

    public SalinityServiceImpl(SalinityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public SampleView submit(SubmitSampleCommand command) {
        validateRanges(command.depthM(), command.salinityPsu());
        Instant now = Instant.now(clock);
        try {
            repository.registerVoyageIfAbsent(command.voyageCode(), now);
        } catch (DuplicateKeyException ignored) {
            // 并发首提：航次已被另一线程注册，继续走幂等判定。
        }

        Optional<SalinitySample> byRequestId = repository.findSampleByRequestId(command.requestId());
        if (byRequestId.isPresent()) {
            SalinitySample existing = byRequestId.get();
            if (!sameBusinessContent(existing, command)) {
                throw new ConflictException("requestId 已用于不同内容的采样请求: " + command.requestId());
            }
            return toSampleView(existing, Map.of());
        }

        Optional<SalinitySample> byNo = repository.findSampleByNo(command.sampleNo());
        if (byNo.isPresent()) {
            SalinitySample existing = byNo.get();
            if (!sameBusinessContent(existing, command)) {
                throw new ConflictException("采样编号已存在且内容不同: " + command.sampleNo());
            }
            return toSampleView(existing, Map.of());
        }

        SalinitySample toInsert = new SalinitySample(
                null,
                command.voyageCode(),
                command.stationCode(),
                command.depthM(),
                command.salinityPsu(),
                command.collectedUtc(),
                command.sampleNo(),
                blankToNull(command.requestId()),
                false,
                null,
                now);
        try {
            return toSampleView(repository.insertSample(toInsert), Map.of());
        } catch (DuplicateKeyException ex) {
            // 并发重放：唯一约束兜底，回读后按内容判定幂等或冲突。
            SalinitySample existing = repository.findSampleByNo(command.sampleNo())
                    .or(() -> repository.findSampleByRequestId(command.requestId()))
                    .orElseThrow(() -> ex);
            if (!sameBusinessContent(existing, command)) {
                throw new ConflictException("采样编号或 requestId 已用于不同内容的请求: " + command.sampleNo());
            }
            return toSampleView(existing, Map.of());
        }
    }

    @Override
    @Transactional
    public SampleView adopt(AdoptSampleCommand command) {
        Voyage voyage = repository.lockVoyage(command.voyageCode())
                .orElseThrow(() -> new ResourceNotFoundException("航次不存在: " + command.voyageCode()));
        if (voyage.status() == VoyageStatus.SEALED) {
            throw new ConflictException("航次已封存，不允许替换采纳版本: " + command.voyageCode());
        }

        SalinitySample target = repository.findSampleByNo(command.sampleNo())
                .orElseThrow(() -> new ResourceNotFoundException("样本不存在: " + command.sampleNo()));
        if (!target.voyageCode().equals(command.voyageCode())) {
            throw new BusinessRuleException(
                    "跨航次质控：样本 " + command.sampleNo() + " 属于航次 " + target.voyageCode()
                            + "，不能在航次 " + command.voyageCode() + " 采纳");
        }

        // 锁定站位深度全部样本行，与封存快照互斥；同位置并发采纳在此串行。
        List<SalinitySample> positionRows = repository.lockPosition(
                voyage.voyageCode(), target.stationCode(), target.depthM());
        Optional<SalinitySample> currentAdopted = positionRows.stream()
                .filter(SalinitySample::adopted)
                .findFirst();

        if (currentAdopted.isPresent() && !currentAdopted.get().id().equals(target.id())) {
            repository.demoteAdopted(voyage.voyageCode(), target.stationCode(), target.depthM(), target.id());
        }
        if (target.id() != null && (currentAdopted.isEmpty()
                || !currentAdopted.get().id().equals(target.id()))) {
            repository.markAdopted(target.id());
        }

        Map<Long, String> sampleNoById = positionRows.stream()
                .collect(Collectors.toMap(SalinitySample::id, SalinitySample::sampleNo));
        SalinitySample adopted = repository.findAdopted(
                voyage.voyageCode(), target.stationCode(), target.depthM())
                .orElseThrow(() -> new IllegalStateException("采纳后未读到采纳版本"));
        return toSampleView(adopted, sampleNoById);
    }

    @Override
    @Transactional
    public void seal(SealVoyageCommand command) {
        Voyage voyage = repository.lockVoyage(command.voyageCode())
                .orElseThrow(() -> new ResourceNotFoundException("航次不存在: " + command.voyageCode()));
        if (voyage.status() == VoyageStatus.SEALED) {
            throw new ConflictException("航次已封存: " + command.voyageCode());
        }

        // 对全部采纳行加锁，得到封存时刻一致的版本集合；航次行锁已让本事务与采纳事务串行。
        List<SalinitySample> adoptedSnapshot = repository.lockAndLoadAdoptedSnapshot(voyage.voyageCode());
        List<SalinitySample> allSamples = repository.findSamples(voyage.voyageCode());

        Set<String> stations = allSamples.stream()
                .map(SalinitySample::stationCode)
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        Map<String, Map<Integer, SalinitySample>> adoptedByStationDepth = new LinkedHashMap<>();
        for (SalinitySample sample : adoptedSnapshot) {
            adoptedByStationDepth
                    .computeIfAbsent(sample.stationCode(), key -> new LinkedHashMap<>())
                    .put(sample.depthM(), sample);
        }

        List<String> missing = new ArrayList<>();
        for (String station : stations) {
            Map<Integer, SalinitySample> atStation =
                    adoptedByStationDepth.getOrDefault(station, Map.of());
            for (Integer depth : REQUIRED_DEPTHS) {
                if (!atStation.containsKey(depth)) {
                    missing.add("站位 " + station + " 缺 " + depth + " 米采纳样本");
                }
            }
        }
        if (stations.isEmpty()) {
            missing.add("航次没有任何样本，无法封存");
        }
        if (!missing.isEmpty()) {
            // 抛出即回滚，航次保持 OPEN，不写入任何剖面点。
            throw new BusinessRuleException("封存条件不满足: " + String.join("; ", missing));
        }

        Instant sealedAt = Instant.now(clock);
        List<SealedProfilePoint> points = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, SalinitySample>> entry : adoptedByStationDepth.entrySet()) {
            for (Integer depth : REQUIRED_DEPTHS) {
                SalinitySample sample = entry.getValue().get(depth);
                points.add(new SealedProfilePoint(
                        null,
                        voyage.voyageCode(),
                        entry.getKey(),
                        depth,
                        sample.id(),
                        sample.salinityPsu(),
                        sample.collectedUtc(),
                        sealedAt));
            }
        }
        repository.insertProfilePoints(points);
        repository.markSealed(voyage.voyageCode(), sealedAt);
    }

    @Override
    @Transactional
    public void addErratum(AddErratumCommand command) {
        Voyage voyage = repository.findVoyage(command.voyageCode())
                .orElseThrow(() -> new ResourceNotFoundException("航次不存在: " + command.voyageCode()));
        if (voyage.status() != VoyageStatus.SEALED || voyage.sealedUtc() == null) {
            throw new BusinessRuleException("航次未封存，不能追加勘误: " + command.voyageCode());
        }
        repository.insertErratum(new ProfileErratum(
                null,
                voyage.voyageCode(),
                command.note(),
                voyage.sealedUtc(),
                Instant.now(clock)));
    }

    @Override
    public VoyageDataView query(String voyageCode) {
        Voyage voyage = repository.findVoyage(voyageCode)
                .orElseThrow(() -> new ResourceNotFoundException("航次不存在: " + voyageCode));
        List<SalinitySample> samples = repository.findSamples(voyageCode);
        Map<Long, String> sampleNoById = samples.stream()
                .collect(Collectors.toMap(SalinitySample::id, SalinitySample::sampleNo));

        List<SampleView> sampleViews = samples.stream()
                .map(sample -> toSampleView(sample, sampleNoById))
                .toList();

        List<AdoptedSampleView> adoptedViews = samples.stream()
                .filter(SalinitySample::adopted)
                .sorted(Comparator.comparing(SalinitySample::stationCode)
                        .thenComparing(SalinitySample::depthM))
                .map(sample -> new AdoptedSampleView(
                        sample.sampleNo(),
                        sample.stationCode(),
                        sample.depthM(),
                        sample.salinityPsu(),
                        sample.collectedUtc()))
                .toList();

        List<FrozenProfilePointView> frozenViews = repository.findProfilePoints(voyageCode).stream()
                .map(point -> new FrozenProfilePointView(
                        point.stationCode(),
                        point.depthM(),
                        sampleNoById.getOrDefault(point.sampleId(), String.valueOf(point.sampleId())),
                        point.salinityPsu(),
                        point.collectedUtc()))
                .toList();

        List<ErratumView> erratumViews = repository.findErrata(voyageCode).stream()
                .map(erratum -> new ErratumView(
                        erratum.note(),
                        erratum.refSealedUtc(),
                        erratum.createdUtc()))
                .toList();

        return new VoyageDataView(
                voyage.voyageCode(),
                voyage.status() == VoyageStatus.SEALED,
                voyage.sealedUtc(),
                sampleViews,
                adoptedViews,
                frozenViews,
                erratumViews);
    }

    private void validateRanges(Integer depthM, BigDecimal salinityPsu) {
        if (depthM == null || depthM < MIN_DEPTH_M || depthM > MAX_DEPTH_M) {
            throw new BusinessRuleException("深度超出允许范围 0 至 500 米: " + depthM);
        }
        if (salinityPsu == null
                || salinityPsu.compareTo(MIN_SALINITY) < 0
                || salinityPsu.compareTo(MAX_SALINITY) > 0) {
            throw new BusinessRuleException("盐度超出允许范围 0 至 45 PSU: " + salinityPsu);
        }
    }

    private boolean sameBusinessContent(SalinitySample existing, SubmitSampleCommand command) {
        return existing.voyageCode().equals(command.voyageCode())
                && existing.stationCode().equals(command.stationCode())
                && existing.depthM().equals(command.depthM())
                && existing.salinityPsu().compareTo(command.salinityPsu()) == 0
                && existing.collectedUtc().equals(command.collectedUtc())
                && existing.sampleNo().equals(command.sampleNo());
    }

    private SampleView toSampleView(SalinitySample sample, Map<Long, String> sampleNoById) {
        return new SampleView(
                sample.sampleNo(),
                sample.stationCode(),
                sample.depthM(),
                sample.salinityPsu(),
                sample.collectedUtc(),
                sample.adopted(),
                sample.supersededBy() == null ? null : sampleNoById.get(sample.supersededBy()),
                sample.requestId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
