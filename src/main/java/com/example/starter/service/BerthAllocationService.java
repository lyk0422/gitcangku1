package com.example.starter.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.ApplicationStatus;
import com.example.starter.domain.RejectionCode;
import com.example.starter.po.ApplicationPo;
import com.example.starter.po.BerthPo;
import com.example.starter.po.ReviewRecordPo;
import com.example.starter.po.TideSegmentPo;
import com.example.starter.po.TideVersionPo;
import com.example.starter.repository.ApplicationRepository;
import com.example.starter.repository.BerthRepository;
import com.example.starter.repository.ReviewRecordRepository;
import com.example.starter.repository.TideVersionRepository;
import com.example.starter.web.ApiException;
import com.example.starter.web.ApplicationResponse;
import com.example.starter.web.ApplyRequest;
import com.example.starter.web.BerthTimelineResponse;
import com.example.starter.web.ReviewRequest;
import com.example.starter.web.TidePublishRequest;
import com.example.starter.web.TideVersionResponse;

/**
 * 泊位靠泊分配核心服务：申请校核、潮高版本发布、潮高修订联动、人工复核与查询。
 *
 * <p>申请与潮高发布、复核事务通过 alloc_lock 单行表锁串行化，保证校核只能基于已提交的
 * 潮高版本，不会用旧校核结果占用新版本下不合格的泊位。
 */
@Service
public class BerthAllocationService {

    private final BerthRepository berthRepository;
    private final TideVersionRepository tideVersionRepository;
    private final ApplicationRepository applicationRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public BerthAllocationService(BerthRepository berthRepository,
                                  TideVersionRepository tideVersionRepository,
                                  ApplicationRepository applicationRepository,
                                  ReviewRecordRepository reviewRecordRepository,
                                  JdbcTemplate jdbcTemplate,
                                  Clock clock) {
        this.berthRepository = berthRepository;
        this.tideVersionRepository = tideVersionRepository;
        this.applicationRepository = applicationRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 提交靠泊申请：按当前已提交潮高版本的整个区间最低潮高校核，确认占泊或拒绝（不占泊）。
     * 同一 requestId 同参重放首次结果，异参返回 409。
     */
    @Transactional
    public ApplicationResponse apply(ApplyRequest request) {
        acquireGlobalLock();
        ApplicationPo existing = applicationRepository.findByRequestId(request.requestId());
        String hash = applyHash(request);
        if (existing != null) {
            if (!existing.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_PARAM_MISMATCH",
                        "同一 requestId 的请求参数与首次提交不一致");
            }
            return toResponse(existing);
        }
        validateApplyRequest(request);

        LocalDateTime now = LocalDateTime.now(clock);
        BerthPo berth = berthRepository.findByCode(request.berthCode());
        if (berth == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BERTH_NOT_FOUND",
                    "泊位不存在：" + request.berthCode());
        }

        TideVersionPo current = tideVersionRepository.findCurrent();
        List<TideSegmentPo> segments = current == null ? List.of()
                : tideVersionRepository.findSegments(current.id());
        if (current == null || !current.rangeStart().isBefore(request.endTime())
                || !TideCalculator.covers(segments, request.startTime(), request.endTime())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TIDE_GAP",
                    "申请区间缺少完整覆盖的有效潮高数据（潮高区间存在缺口或未发布潮高版本）");
        }

        BigDecimal minTide = TideCalculator.minHeight(segments, request.startTime(), request.endTime());
        BigDecimal available = berth.staticDepth().add(minTide);
        BigDecimal clearance = available.subtract(request.draft().add(request.safetyMargin()));

        ApplicationPo po = new ApplicationPo(
                null, request.requestId(), request.vesselName(), request.draft(),
                request.safetyMargin(), request.berthCode(), request.startTime(), request.endTime(),
                ApplicationStatus.REJECTED.name(), null, null,
                berth.staticDepth(), current.id(), current.versionNo(), minTide, available,
                clearance, null, null, null, hash, now, now, null, null);

        if (clearance.compareTo(BigDecimal.ZERO) < 0) {
            po = withRejection(po, RejectionCode.DEPTH_INSUFFICIENT,
                    String.format("可用深度 %.2f 米不足：吃水 %.2f 米 + 安全余量 %.2f 米，余量 %.2f 米",
                            available, request.draft(), request.safetyMargin(), clearance));
        } else if (applicationRepository.countConfirmedOverlap(
                request.berthCode(), request.startTime(), request.endTime(), null) > 0) {
            po = withRejection(po, RejectionCode.BERTH_OCCUPIED,
                    "同一泊位该时段已存在已确认靠泊，区间重叠");
        } else {
            po = new ApplicationPo(
                    po.id(), po.requestId(), po.vesselName(), po.draft(), po.safetyMargin(),
                    po.berthCode(), po.startTime(), po.endTime(),
                    ApplicationStatus.CONFIRMED.name(), null, null, po.staticDepth(),
                    po.tideVersionId(), po.tideVersionNo(), po.minTideHeight(),
                    po.availableDepth(), po.clearance(),
                    current.versionNo(), minTide, available, po.requestHash(),
                    now, now, now, null);
        }
        applicationRepository.insert(po);
        return toResponse(po);
    }

    /**
     * 发布新潮高版本：校验区间必须为正时长且首尾相接无缺口；随后联动尚未开始的已确认靠泊，
     * 新版本下深度不足或潮高缺口者标记待复核并释放占用；已开始的记录保留原决策快照。
     */
    @Transactional
    public TideVersionResponse publishTide(TidePublishRequest request) {
        acquireGlobalLock();
        List<TideSegmentPo> segments = validateAndNormalize(request);

        LocalDateTime now = LocalDateTime.now(clock);
        TideVersionPo version = tideVersionRepository.insertVersion(
                request.rangeStart(), request.rangeEnd(), now, segments);

        for (ApplicationPo app : applicationRepository.findConfirmedStartingAfter(now)) {
            ApplicationPo revised = reviseIfNeeded(app, version, now);
            if (revised != null) {
                applicationRepository.update(revised);
            }
        }

        List<TideVersionResponse.SegmentView> views = segments.stream()
                .map(s -> new TideVersionResponse.SegmentView(s.startTime(), s.endTime(), s.height()))
                .toList();
        return new TideVersionResponse(version.versionNo(), version.rangeStart(),
                version.rangeEnd(), version.status(), version.publishedAt(), views);
    }

    /**
     * 人工复核：复核通过则按当前已提交潮高版本重新校核并确认占泊，拒绝则释放并终结申请。
     * 同一 requestId 同参重放首次复核结果，异参 409。
     */
    @Transactional
    public ApplicationResponse review(ReviewRequest request) {
        acquireGlobalLock();
        String hash = reviewHash(request);
        ReviewRecordPo existingReview = reviewRecordRepository.findByRequestId(request.requestId());
        if (existingReview != null) {
            if (!existingReview.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_PARAM_MISMATCH",
                        "同一 requestId 的复核参数与首次提交不一致");
            }
            return toResponse(applicationRepository.lockByRequestId(request.requestId()));
        }

        ApplicationPo app = applicationRepository.lockByRequestId(request.requestId());
        if (app == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                    "靠泊申请不存在：" + request.requestId());
        }
        if (!ApplicationStatus.PENDING_REVIEW.name().equals(app.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_NOT_REQUIRED",
                    "该申请当前不处于待复核状态");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (!request.approved()) {
            ApplicationPo rejected = copyApplication(app, ApplicationStatus.REJECTED.name(),
                    RejectionCode.MANUAL_REJECT.name(), "人工复核拒绝，不占用泊位",
                    app.staticDepth(), app.tideVersionId(), app.tideVersionNo(),
                    app.minTideHeight(), app.availableDepth(), app.clearance(),
                    app.snapTideVersionNo(), app.snapMinTideHeight(), app.snapAvailableDepth(),
                    app.confirmedAt(), now);
            applicationRepository.update(rejected);
            reviewRecordRepository.insert(new ReviewRecordPo(
                    null, request.requestId(), "REJECT", hash, now));
            return toResponse(rejected);
        }

        RecheckResult recheck = recheckAgainstCurrent(app);
        if (recheck.depthRejected() != null) {
            throw recheck.depthRejected();
        }
        ApplicationPo confirmed = copyApplication(
                app, ApplicationStatus.CONFIRMED.name(), null, null,
                recheck.berthStaticDepth(), recheck.version().id(), recheck.version().versionNo(),
                recheck.minTide(), recheck.available(), recheck.clearance(),
                recheck.version().versionNo(), recheck.minTide(), recheck.available(),
                now, now);
        applicationRepository.update(confirmed);
        reviewRecordRepository.insert(new ReviewRecordPo(
                null, request.requestId(), "APPROVE", hash, now));
        return toResponse(confirmed);
    }

    /**
     * 查询申请详情。
     */
    public ApplicationResponse getApplication(String requestId) {
        ApplicationPo po = applicationRepository.findByRequestId(requestId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                    "靠泊申请不存在：" + requestId);
        }
        return toResponse(po);
    }

    /**
     * 查询泊位时间轴：泊位静态水深、当前潮高版本与区间、该泊位全部申请。
     */
    public BerthTimelineResponse berthTimeline(String berthCode) {
        BerthPo berth = berthRepository.findByCode(berthCode);
        if (berth == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BERTH_NOT_FOUND",
                    "泊位不存在：" + berthCode);
        }
        BerthTimelineResponse.TideVersionView tideView = null;
        TideVersionPo current = tideVersionRepository.findCurrent();
        if (current != null) {
            List<TideVersionResponse.SegmentView> views = tideVersionRepository
                    .findSegments(current.id()).stream()
                    .map(s -> new TideVersionResponse.SegmentView(s.startTime(), s.endTime(), s.height()))
                    .toList();
            tideView = new BerthTimelineResponse.TideVersionView(
                    current.versionNo(), current.rangeStart(), current.rangeEnd(),
                    current.publishedAt(), views);
        }
        List<ApplicationResponse> apps = applicationRepository.findByBerth(berthCode).stream()
                .map(this::toResponse)
                .toList();
        return new BerthTimelineResponse(berth.code(), berth.staticDepth(), tideView, apps);
    }

    /**
     * 用新版本重新校核未开始的已确认靠泊；返回需要落库的新状态，仍合格返回 null（保持确认）。
     */
    private ApplicationPo reviseIfNeeded(ApplicationPo app, TideVersionPo newVersion, LocalDateTime now) {
        List<TideSegmentPo> segments = tideVersionRepository.findSegments(newVersion.id());
        boolean covered = !app.startTime().isBefore(newVersion.rangeStart())
                && !app.endTime().isAfter(newVersion.rangeEnd())
                && TideCalculator.covers(segments, app.startTime(), app.endTime());
        if (!covered) {
            return copyApplication(
                    app, ApplicationStatus.PENDING_REVIEW.name(), null, null,
                    app.staticDepth(), newVersion.id(), newVersion.versionNo(),
                    app.minTideHeight(), app.availableDepth(), app.clearance(),
                    app.snapTideVersionNo(), app.snapMinTideHeight(), app.snapAvailableDepth(),
                    app.confirmedAt(), app.reviewedAt());
        }
        BigDecimal minTide = TideCalculator.minHeight(segments, app.startTime(), app.endTime());
        BigDecimal available = app.staticDepth().add(minTide);
        BigDecimal clearance = available.subtract(app.draft().add(app.safetyMargin()));
        if (clearance.compareTo(BigDecimal.ZERO) >= 0) {
            return null;
        }
        return copyApplication(
                app, ApplicationStatus.PENDING_REVIEW.name(), null, null,
                app.staticDepth(), newVersion.id(), newVersion.versionNo(),
                minTide, available, clearance,
                app.snapTideVersionNo(), app.snapMinTideHeight(), app.snapAvailableDepth(),
                app.confirmedAt(), app.reviewedAt());
    }

    /**
     * 复核通过时按当前已提交潮高版本重新校核深度与泊位占用。
     */
    private RecheckResult recheckAgainstCurrent(ApplicationPo app) {
        TideVersionPo current = tideVersionRepository.findCurrent();
        List<TideSegmentPo> segments = current == null ? List.of()
                : tideVersionRepository.findSegments(current.id());
        if (current == null || app.startTime().isBefore(current.rangeStart())
                || app.endTime().isAfter(current.rangeEnd())
                || !TideCalculator.covers(segments, app.startTime(), app.endTime())) {
            return new RecheckResult(null, null, null, null, null,
                    new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TIDE_GAP",
                            "当前潮高版本未完整覆盖申请区间，无法完成复核校核"));
        }
        BerthPo berth = berthRepository.findByCode(app.berthCode());
        BigDecimal minTide = TideCalculator.minHeight(segments, app.startTime(), app.endTime());
        BigDecimal available = berth.staticDepth().add(minTide);
        BigDecimal clearance = available.subtract(app.draft().add(app.safetyMargin()));
        if (clearance.compareTo(BigDecimal.ZERO) < 0) {
            return new RecheckResult(null, null, null, null, null,
                    new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            RejectionCode.TIDE_REVISION_DEPTH_INSUFFICIENT.name(),
                            String.format("新潮高版本下可用深度 %.2f 米不足，余量 %.2f 米，不能确认靠泊",
                                    available, clearance)));
        }
        if (applicationRepository.countConfirmedOverlap(
                app.berthCode(), app.startTime(), app.endTime(), app.requestId()) > 0) {
            return new RecheckResult(null, null, null, null, null,
                    new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BERTH_OCCUPIED",
                            "原窗口已被其他已确认靠泊占用，不能确认靠泊"));
        }
        return new RecheckResult(current, berth.staticDepth(), minTide, available, clearance, null);
    }

    private void validateApplyRequest(ApplyRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                    "靠泊结束时间必须晚于开始时间");
        }
        if (request.draft().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DRAFT",
                    "船舶吃水必须为正数");
        }
        if (request.safetyMargin().compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MARGIN",
                    "安全余量不得为负");
        }
    }

    /**
     * 校验发布区间：版本范围为正时长，每个区间为正时长，按起点排序后首尾相接、
     * 恰好覆盖 [rangeStart, rangeEnd)，无缺口无重叠。
     */
    private List<TideSegmentPo> validateAndNormalize(TidePublishRequest request) {
        if (request.rangeStart() == null || request.rangeEnd() == null
                || !request.rangeEnd().isAfter(request.rangeStart())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                    "潮高版本覆盖范围结束时间必须晚于开始时间");
        }
        List<TidePublishRequest.Segment> sorted = request.segments().stream()
                .sorted((a, b) -> a.startTime().compareTo(b.startTime()))
                .toList();
        LocalDateTime cursor = request.rangeStart();
        for (TidePublishRequest.Segment segment : sorted) {
            if (!segment.endTime().isAfter(segment.startTime())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                        "潮高区间时长必须为正");
            }
            if (!segment.startTime().equals(cursor)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TIDE_GAP",
                        "潮高区间存在缺口或重叠，必须首尾相接覆盖整个版本时间轴");
            }
            cursor = segment.endTime();
        }
        if (!cursor.equals(request.rangeEnd())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TIDE_GAP",
                    "潮高区间未恰好覆盖版本时间轴终点，存在缺口或溢出");
        }
        // id/versionId 在落库时回填
        return sorted.stream()
                .map(s -> new TideSegmentPo(null, null, s.startTime(), s.endTime(), s.height()))
                .toList();
    }

    private void acquireGlobalLock() {
        jdbcTemplate.queryForObject("SELECT id FROM alloc_lock WHERE id = 1 FOR UPDATE",
                Long.class);
    }

    private ApplicationPo withRejection(ApplicationPo po, RejectionCode code, String reason) {
        return copyApplication(po, ApplicationStatus.REJECTED.name(), code.name(), reason,
                po.staticDepth(), po.tideVersionId(), po.tideVersionNo(), po.minTideHeight(),
                po.availableDepth(), po.clearance(), po.snapTideVersionNo(),
                po.snapMinTideHeight(), po.snapAvailableDepth(),
                null, null);
    }

    private ApplicationResponse toResponse(ApplicationPo po) {
        return new ApplicationResponse(
                po.requestId(), po.vesselName(), po.draft(), po.safetyMargin(), po.berthCode(),
                po.startTime(), po.endTime(), po.status(), po.rejectionCode(), po.rejectionReason(),
                po.staticDepth(), po.tideVersionNo(), po.minTideHeight(), po.availableDepth(),
                po.clearance(), po.snapTideVersionNo(), po.snapMinTideHeight(),
                po.snapAvailableDepth(), po.createdAt(), po.updatedAt(),
                po.confirmedAt(), po.reviewedAt());
    }

    private ApplicationPo copyApplication(ApplicationPo po, String status, String rejectionCode,
                                          String rejectionReason, BigDecimal staticDepth,
                                          Long tideVersionId, Long tideVersionNo,
                                          BigDecimal minTideHeight, BigDecimal availableDepth,
                                          BigDecimal clearance, Long snapVersionNo,
                                          BigDecimal snapMinTide, BigDecimal snapAvailable,
                                          LocalDateTime confirmedAt, LocalDateTime reviewedAt) {
        return new ApplicationPo(
                po.id(), po.requestId(), po.vesselName(), po.draft(), po.safetyMargin(),
                po.berthCode(), po.startTime(), po.endTime(), status, rejectionCode, rejectionReason,
                staticDepth, tideVersionId, tideVersionNo, minTideHeight, availableDepth, clearance,
                snapVersionNo, snapMinTide, snapAvailable, po.requestHash(),
                po.createdAt(), LocalDateTime.now(clock), confirmedAt, reviewedAt);
    }

    private String applyHash(ApplyRequest request) {
        return sha256(String.join("|",
                request.requestId(), request.vesselName(),
                request.draft().stripTrailingZeros().toPlainString(),
                request.safetyMargin().stripTrailingZeros().toPlainString(),
                request.berthCode(), request.startTime().toString(), request.endTime().toString()));
    }

    private String reviewHash(ReviewRequest request) {
        return sha256(request.requestId() + "|" + request.approved());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 复核校核中间结果；depthRejected 非空表示无法确认（缺口/深度不足/占用）。
     */
    private record RecheckResult(TideVersionPo version, BigDecimal berthStaticDepth,
                                 BigDecimal minTide, BigDecimal available, BigDecimal clearance,
                                 ApiException depthRejected) {
    }
}
