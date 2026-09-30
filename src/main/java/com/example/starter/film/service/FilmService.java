package com.example.starter.film.service;

import com.example.starter.film.exception.ConflictException;
import com.example.starter.film.exception.NotFoundException;
import com.example.starter.film.exception.UnprocessableException;
import com.example.starter.film.model.Film;
import com.example.starter.film.model.Master;
import com.example.starter.film.model.MasterSegment;
import com.example.starter.film.model.ReviewRequest;
import com.example.starter.film.model.ReviewSegmentBaseline;
import com.example.starter.film.model.ReviewStatus;
import com.example.starter.film.model.SegmentVersion;
import com.example.starter.film.repo.DeliveryRepository;
import com.example.starter.film.repo.FilmRepository;
import com.example.starter.film.repo.IdempotentRequestRepository;
import com.example.starter.film.repo.MasterRepository;
import com.example.starter.film.repo.ReviewRepository;
import com.example.starter.film.repo.SegmentVersionRepository;
import com.example.starter.film.support.Payloads;
import com.example.starter.film.support.ConcurrencyHooks;
import com.example.starter.film.support.TimeSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 影片修复版本核心业务：片段提交（append-only）、整片审核（基线固定+不可变母版）、交付冻结。
 *
 * <p>同影片的提交与审核通过 {@code film} 行级锁串行化，保证审核看到的是同一瞬间的完整片段集合；
 * 任一片段更新会使该影片所有 PENDING 审核过期，杜绝新旧片段拼成未声明版本。</p>
 */
@Service
public class FilmService {

    /** 幂等操作：片段提交。 */
    public static final String OP_SEGMENT_SUBMIT = "SEGMENT_SUBMIT";
    /** 幂等操作：创建审核请求。 */
    public static final String OP_REVIEW_CREATE = "REVIEW_CREATE";
    /** 幂等操作：审核通过。 */
    public static final String OP_REVIEW_APPROVE = "REVIEW_APPROVE";
    /** 幂等操作：撤销审核。 */
    public static final String OP_REVIEW_REVOKE = "REVIEW_REVOKE";

    private final FilmRepository films;
    private final SegmentVersionRepository segments;
    private final ReviewRepository reviews;
    private final MasterRepository masters;
    private final DeliveryRepository deliveries;
    private final IdempotentRequestRepository idempotents;
    private final TimeSource timeSource;
    private final ConcurrencyHooks hooks;

    public FilmService(FilmRepository films, SegmentVersionRepository segments,
                       ReviewRepository reviews, MasterRepository masters,
                       DeliveryRepository deliveries,
                       IdempotentRequestRepository idempotents, TimeSource timeSource,
                       ConcurrencyHooks hooks) {
        this.films = films;
        this.segments = segments;
        this.reviews = reviews;
        this.masters = masters;
        this.deliveries = deliveries;
        this.idempotents = idempotents;
        this.timeSource = timeSource;
        this.hooks = hooks;
    }

    /**
     * 建档。
     */
    @Transactional
    public Film createFilm(String code, String title, int plannedSegments) {
        try {
            return films.insert(code, title, plannedSegments, timeSource.now());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("影片编号已存在: " + code);
        }
    }

    public Film getFilm(long filmId) {
        return films.findById(filmId)
                .orElseThrow(() -> new NotFoundException("影片不存在: " + filmId));
    }

    /**
     * 片段修复提交：校验序号与基于版次后追加新版次，并使该片所有未决审核过期。
     * 同一 requestId 同参返回原版次，异参 409。
     */
    @Transactional
    public SegmentSubmitOutcome submitSegment(long filmId, int seq, String digest,
                                              int baseVersion, String requestId) {
        hooks.runBeforeSubmitLock();
        Film film = lockFilm(filmId);
        hooks.runInsideSubmitAfterLock();

        String payloadHash = Payloads.segmentSubmit(filmId, seq, digest, baseVersion);
        var existing = idempotents.find(requestId, OP_SEGMENT_SUBMIT);
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的提交参数不一致");
            }
            SegmentVersion replayed = replaySegment(existing.get().resultRef(), requestId);
            return new SegmentSubmitOutcome(replayed, 0);
        }

        // 422：序号超出计划范围
        if (seq < 1 || seq > film.plannedSegments()) {
            throw new UnprocessableException(
                    "片段序号超出范围: 1-" + film.plannedSegments());
        }

        // 409：必须基于当前版次（首次为 0）
        int currentVersion = segments.findLatest(filmId, seq)
                .map(SegmentVersion::versionNo).orElse(0);
        if (baseVersion != currentVersion) {
            throw new ConflictException(
                    "基于版次 " + baseVersion + " 不是当前版次 " + currentVersion);
        }

        // 先占幂等键：并发同键事务在此阻塞，提交后唯一冲突走重放
        if (!idempotents.tryInsert(requestId, OP_SEGMENT_SUBMIT, filmId,
                payloadHash, timeSource.now())) {
            var again = idempotents.find(requestId, OP_SEGMENT_SUBMIT).orElseThrow();
            if (!again.payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的提交参数不一致");
            }
            SegmentVersion replayed = replaySegment(again.resultRef(), requestId);
            return new SegmentSubmitOutcome(replayed, 0);
        }

        SegmentVersion version;
        try {
            version = segments.insert(filmId, seq, currentVersion + 1, digest,
                    requestId, timeSource.now());
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底（requestId 跨操作复用、并发版次竞争等），不修改任何既有版次
            throw new ConflictException("提交冲突：requestId已使用或版次已被并发更新");
        }

        // 任一片段更新：尚未审核（PENDING）的审核请求全部过期
        int expiredCount = reviews.markPendingExpired(filmId);

        idempotents.updateResultRef(requestId, OP_SEGMENT_SUBMIT, String.valueOf(version.id()));
        return new SegmentSubmitOutcome(version, expiredCount);
    }

    /**
     * 创建整片审核请求：要求所有计划片段均已有有效提交，并按序号固定每段版次与摘要基线。
     * 缺段返回 422，不写入任何审核数据。
     */
    @Transactional
    public ReviewRequest createReview(long filmId, String requestId) {
        Film film = lockFilm(filmId);

        String payloadHash = Payloads.reviewCreate(filmId);
        var existing = idempotents.find(requestId, OP_REVIEW_CREATE);
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的审核参数不一致");
            }
            return replayReview(existing.get().resultRef());
        }

        Map<Integer, SegmentVersion> latest = latestCompleteOrFail(film);

        if (!idempotents.tryInsert(requestId, OP_REVIEW_CREATE, filmId,
                payloadHash, timeSource.now())) {
            var again = idempotents.find(requestId, OP_REVIEW_CREATE).orElseThrow();
            if (!again.payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的审核参数不一致");
            }
            return replayReview(again.resultRef());
        }

        ReviewRequest review = reviews.insert(filmId, requestId, timeSource.now());
        for (int seq = 1; seq <= film.plannedSegments(); seq++) {
            SegmentVersion v = latest.get(seq);
            reviews.insertBaseline(review.id(), seq, v.versionNo(), v.digest(),
                    timeSource.now());
        }
        idempotents.updateResultRef(requestId, OP_REVIEW_CREATE, String.valueOf(review.id()));
        return review;
    }

    /**
     * 审核通过：基线必须仍与当前最新片段集合逐段一致（版次+摘要）。
     * 一致则在同一事务内生成不可变母版及全部快照；任一片段漂移则审核置 EXPIRED 并 409，
     * 绝不留下部分母版，也不混用前后摘要。
     */
    @Transactional
    public ApproveOutcome approveReview(long filmId, long reviewId, String requestId) {
        hooks.runBeforeApproveLock();
        Film film = lockFilm(filmId);
        hooks.runInsideApproveAfterLock();
        ReviewRequest review = reviews.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("审核请求不存在: " + reviewId));
        if (review.filmId() != filmId) {
            throw new NotFoundException("审核请求不属于该影片");
        }

        String payloadHash = Payloads.reviewApprove(filmId, reviewId);
        var existing = idempotents.find(requestId, OP_REVIEW_APPROVE);
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的审核通过参数不一致");
            }
            return replayApprove(review);
        }

        if (review.status() == ReviewStatus.APPROVED) {
            // 无幂等键记录却已通过：按状态冲突处理，避免重复生成母版
            throw new ConflictException("审核已通过，不能重复通过");
        }
        if (review.status() == ReviewStatus.REVOKED) {
            throw new ConflictException("审核已撤销，不能通过");
        }
        if (review.status() == ReviewStatus.EXPIRED) {
            throw new ConflictException("审核已因片段更新过期，请重新发起审核");
        }

        List<ReviewSegmentBaseline> baselines = reviews.findBaselines(reviewId);
        Map<Integer, SegmentVersion> latest = latestMap(filmId);
        // 持影片行锁读到的片段集合即同一瞬间集合；基线必须与之一致，否则冲突失败不写母版
        for (ReviewSegmentBaseline b : baselines) {
            SegmentVersion current = latest.get(b.seq());
            if (current == null
                    || current.versionNo() != b.versionNo()
                    || !current.digest().equals(b.digest())) {
                throw new ConflictException("片段在审核后发生变化，审核已过期，请重新发起审核");
            }
        }
        if (baselines.size() != film.plannedSegments()) {
            throw new UnprocessableException("审核基线不完整");
        }

        if (!idempotents.tryInsert(requestId, OP_REVIEW_APPROVE, filmId,
                payloadHash, timeSource.now())) {
            var again = idempotents.find(requestId, OP_REVIEW_APPROVE).orElseThrow();
            if (!again.payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的审核通过参数不一致");
            }
            return replayApprove(reviews.findById(reviewId).orElseThrow());
        }

        String masterNo = "M-" + film.code() + "-" + reviewId;
        Master master = masters.insert(masterNo, filmId, reviewId,
                film.plannedSegments(), timeSource.now());
        for (ReviewSegmentBaseline b : baselines) {
            masters.insertSegment(master.id(), b.seq(), b.versionNo(), b.digest());
        }
        if (!reviews.markApproved(reviewId, master.id(), timeSource.now())) {
            // 并发改变了审核状态：本事务回滚，母版随之一并撤销
            throw new ConflictException("审核状态已被并发改变");
        }
        idempotents.updateResultRef(requestId, OP_REVIEW_APPROVE, String.valueOf(master.id()));
        return new ApproveOutcome(reviews.findById(reviewId).orElseThrow(), master);
    }

    /**
     * 撤销已通过审核：只阻止之后的新交付，不影响既有回执。
     */
    @Transactional
    public ReviewRequest revokeReview(long filmId, long reviewId, String requestId) {
        lockFilm(filmId);
        ReviewRequest review = reviews.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("审核请求不存在: " + reviewId));
        if (review.filmId() != filmId) {
            throw new NotFoundException("审核请求不属于该影片");
        }

        String payloadHash = Payloads.reviewRevoke(filmId, reviewId);
        var existing = idempotents.find(requestId, OP_REVIEW_REVOKE);
        if (existing.isPresent()) {
            if (!existing.get().payloadHash().equals(payloadHash)) {
                throw new ConflictException("同一requestId的撤销参数不一致");
            }
            return reviews.findById(reviewId).orElseThrow();
        }

        if (review.status() == ReviewStatus.REVOKED) {
            throw new ConflictException("审核已撤销");
        }
        if (review.status() != ReviewStatus.APPROVED) {
            throw new ConflictException("只有已通过的审核可以撤销，当前状态: " + review.status());
        }
        if (!idempotents.tryInsert(requestId, OP_REVIEW_REVOKE, filmId,
                payloadHash, timeSource.now())) {
            return reviews.findById(reviewId).orElseThrow();
        }
        if (!reviews.markRevoked(reviewId, timeSource.now())) {
            throw new ConflictException("审核状态已被并发改变");
        }
        return reviews.findById(reviewId).orElseThrow();
    }

    /**
     * 交付冻结：只引用不可变母版编号，快照取自母版而非工作版。
     * 审核撤销后拒绝新交付；同一 requestId 重试不产生第二张回执。
     */
    @Transactional
    public com.example.starter.film.model.DeliveryReceipt deliver(long filmId, String masterNo,
                                                                  String requestId) {
        Film film = lockFilm(filmId);
        Master master = masters.findByNo(masterNo)
                .orElseThrow(() -> new NotFoundException("母版不存在: " + masterNo));
        if (master.filmId() != filmId) {
            throw new NotFoundException("母版不属于该影片");
        }
        ReviewRequest review = reviews.findById(master.reviewId())
                .orElseThrow(() -> new NotFoundException("母版来源审核不存在"));
        if (review.status() == ReviewStatus.REVOKED) {
            throw new ConflictException("审核已撤销，不能新交付");
        }
        if (review.status() != ReviewStatus.APPROVED) {
            throw new ConflictException("母版对应审核未处于通过状态: " + review.status());
        }

        String payloadHash = Payloads.delivery(filmId, masterNo);
        var existing = deliveries.findByRequestId(requestId);
        if (existing.isPresent()) {
            if (!Payloads.delivery(existing.get().filmId(), existing.get().masterNo())
                    .equals(payloadHash)) {
                throw new ConflictException("同一requestId的交付参数不一致");
            }
            return existing.get();
        }

        List<MasterSegment> masterSegments = masters.findSegments(master.id());
        String snapshot = Payloads.snapshot(masterSegments);
        try {
            return deliveries.insert(requestId, filmId, master.id(), masterNo,
                    snapshot, timeSource.now());
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束保证只有一张回执，重放
            var again = deliveries.findByRequestId(requestId).orElseThrow();
            if (!Payloads.delivery(again.filmId(), again.masterNo()).equals(payloadHash)) {
                throw new ConflictException("同一requestId的交付参数不一致");
            }
            return again;
        }
    }

    // ---- 内部辅助 ------------------------------------------------------

    private Film lockFilm(long filmId) {
        return films.lockById(filmId)
                .orElseThrow(() -> new NotFoundException("影片不存在: " + filmId));
    }

    /**
     * 取每个序号的最新版次；缺任一段时 422。
     */
    private Map<Integer, SegmentVersion> latestCompleteOrFail(Film film) {
        Map<Integer, SegmentVersion> latest = latestMap(film.id());
        List<Integer> missing = new ArrayList<>();
        for (int seq = 1; seq <= film.plannedSegments(); seq++) {
            if (!latest.containsKey(seq)) {
                missing.add(seq);
            }
        }
        if (!missing.isEmpty()) {
            throw new UnprocessableException("缺少计划片段的有效提交，序号: " + missing);
        }
        return latest;
    }

    private Map<Integer, SegmentVersion> latestMap(long filmId) {
        Map<Integer, SegmentVersion> map = new HashMap<>();
        for (SegmentVersion v : segments.findLatestEachByFilm(filmId)) {
            map.put(v.seq(), v);
        }
        return map;
    }

    private SegmentVersion replaySegment(String resultRef, String requestId) {
        if (resultRef != null) {
            return segments.findById(Long.parseLong(resultRef))
                    .orElseGet(() -> segments.findByRequestId(requestId).orElseThrow());
        }
        return segments.findByRequestId(requestId)
                .orElseThrow(() -> new ConflictException("幂等结果尚未就绪"));
    }

    private ReviewRequest replayReview(String resultRef) {
        if (resultRef == null) {
            throw new ConflictException("幂等结果尚未就绪");
        }
        return reviews.findById(Long.parseLong(resultRef))
                .orElseThrow(() -> new ConflictException("幂等审核结果丢失"));
    }

    private ApproveOutcome replayApprove(ReviewRequest review) {
        if (review.status() != ReviewStatus.APPROVED || review.masterId() == null) {
            throw new ConflictException("审核尚未通过，当前状态: " + review.status());
        }
        Master master = masters.findById(review.masterId()).orElseThrow();
        return new ApproveOutcome(review, master);
    }
}
