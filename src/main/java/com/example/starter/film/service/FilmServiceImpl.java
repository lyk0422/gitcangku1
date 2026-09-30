package com.example.starter.film.service;

import com.example.starter.film.api.ApiException;
import com.example.starter.film.api.DeliveryRequest;
import com.example.starter.film.api.DeliveryResponse;
import com.example.starter.film.api.FilmView;
import com.example.starter.film.api.MasterSegmentView;
import com.example.starter.film.api.MasterView;
import com.example.starter.film.api.ReceiptView;
import com.example.starter.film.api.ReviewRequest;
import com.example.starter.film.api.ReviewResponse;
import com.example.starter.film.api.SegmentSubmitRequest;
import com.example.starter.film.api.SegmentSubmitResponse;
import com.example.starter.film.domain.DeliveryReceipt;
import com.example.starter.film.domain.Film;
import com.example.starter.film.domain.Master;
import com.example.starter.film.domain.MasterSegment;
import com.example.starter.film.domain.MasterStatus;
import com.example.starter.film.domain.SegmentVersion;
import com.example.starter.film.repo.FilmRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 影片修复业务实现。
 *
 * <p>并发正确性：片段提交与整片审核在同一事务内先对 film 行加行级写锁
 * （SELECT ... FOR UPDATE），因此同一影片上的提交与审核按事务串行。
 * 审核在持锁期间一次性读取各片段最新版次并写入母版快照，母版引用的必然是
 * 同一瞬间的完整片段集合，不会出现新旧片段混用。
 *
 * <p>幂等：片段提交与交付各以 (film_id, request_id) 唯一约束去重；
 * 同一 requestId 同参返回原结果，异参 409。
 */
@Service
public class FilmServiceImpl implements FilmService {

    /** 内容摘要格式：sha256: 加 64 位十六进制。 */
    private static final Pattern DIGEST_PATTERN =
            Pattern.compile("sha256:[0-9a-fA-F]{64}");

    private final FilmRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 测试编排屏障：审核在锁定影片行、完成缺段校验之后，读取最新片段集合之前调用。
     * 生产环境始终为空操作；并发测试借此把片段提交确定地排进行锁等待队列。
     * 声明为静态，以便测试设置代理时与事务方法实际执行的目标实例共享同一屏障。
     */
    public static volatile Runnable reviewBarrier = () -> { };

    public FilmServiceImpl(FilmRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long registerFilm(String code, int plannedSegments) {
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CODE", "影片编号不能为空");
        }
        if (plannedSegments <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PLANNED_SEGMENTS",
                    "计划片段数必须为正整数");
        }
        if (repository.findFilmByCode(code).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "FILM_EXISTS", "影片编号已存在: " + code);
        }
        return repository.createFilm(code, plannedSegments, Instant.now(clock));
    }

    @Override
    @Transactional
    public SegmentSubmitResponse submitSegment(String filmCode, SegmentSubmitRequest request) {
        Film film = requireFilm(filmCode);
        // 锁定影片行：与并发的片段提交、整片审核互斥。
        repository.lockFilm(film.id());

        SegmentVersion existing =
                repository.findSegmentRequest(film.id(), request.requestId()).orElse(null);
        if (existing != null) {
            if (!sameParams(existing, request)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "同一 requestId 的提交参数与首次请求不一致");
            }
            return SegmentSubmitResponse.from(existing, true);
        }

        int segmentNo = request.segmentNo();
        if (segmentNo < 1 || segmentNo > film.plannedSegments()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENT_OUT_OF_RANGE",
                    "片段序号超出计划范围 1.." + film.plannedSegments());
        }
        if (!DIGEST_PATTERN.matcher(request.contentDigest()).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIGEST",
                    "内容摘要必须为 sha256: 加 64 位十六进制");
        }
        if (request.basedOnVersion() != null && request.basedOnVersion() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BASE_VERSION",
                    "基于的版次必须为正整数，首版请省略");
        }

        SegmentVersion latest =
                repository.findLatestSegment(film.id(), segmentNo).orElse(null);
        int expectedBase = latest == null ? 0 : latest.versionNo();
        int actualBase = request.basedOnVersion() == null ? 0 : request.basedOnVersion();
        if (actualBase != expectedBase) {
            // 基于旧版次（或片段尚无版本却声称基于某版次）提交：拒绝，不改写旧版记录。
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "提交基于的版次 " + actualBase + " 与片段 " + segmentNo
                            + " 当前版次 " + expectedBase + " 不一致");
        }

        int newVersion = expectedBase + 1;
        try {
            repository.insertSegmentVersion(film.id(), segmentNo, newVersion,
                    request.contentDigest(), request.basedOnVersion(),
                    request.requestId(), Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同一 requestId 同时提交：按首次请求的参数重放或判冲突。
            SegmentVersion winner =
                    repository.findSegmentRequest(film.id(), request.requestId()).orElseThrow();
            if (!sameParams(winner, request)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "同一 requestId 的提交参数与首次请求不一致");
            }
            return SegmentSubmitResponse.from(winner, true);
        }
        SegmentVersion saved = repository.findLatestSegment(film.id(), segmentNo).orElseThrow();
        return SegmentSubmitResponse.from(saved, false);
    }

    @Override
    @Transactional
    public ReviewResponse review(String filmCode, ReviewRequest request) {
        Film film = requireFilm(filmCode);
        // 与片段提交互斥：要么锁定更新前的完整片段集合，要么看到更新后的集合。
        repository.lockFilm(film.id());

        Master existing =
                repository.findMasterByReviewRequest(film.id(), request.requestId()).orElse(null);
        if (existing != null) {
            return toReviewResponse(existing, true);
        }

        // 已锁定影片行、尚未读取片段集合；测试在此把并发提交排入锁等待队列。
        reviewBarrier.run();

        List<SegmentVersion> latest = repository.findLatestVersions(film.id());
        if (latest.size() != film.plannedSegments()) {
            // 缺段拒绝：此时尚未写入任何母版记录，不存在部分母版。
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_SEGMENTS",
                    "计划 " + film.plannedSegments() + " 段，当前仅有有效提交 "
                            + latest.size() + " 段，不能审核");
        }

        Instant now = Instant.now(clock);
        String masterNo = "M-" + film.id() + "-" + UUID.randomUUID().toString().replace("-", "");
        long masterId;
        try {
            masterId = repository.insertMaster(film.id(), masterNo, request.requestId(), now);
        } catch (DuplicateKeyException e) {
            // 并发下同一审核 requestId 同时通过：重放首个事务生成的母版。
            Master winner =
                    repository.findMasterByReviewRequest(film.id(), request.requestId()).orElseThrow();
            return toReviewResponse(winner, true);
        }
        List<MasterSegmentView> views = new ArrayList<>(latest.size());
        for (SegmentVersion v : latest) {
            repository.insertMasterSegment(masterId, v.segmentNo(), v.versionNo(), v.contentDigest());
            views.add(new MasterSegmentView(v.segmentNo(), v.versionNo(), v.contentDigest()));
        }
        return new ReviewResponse(masterNo, MasterStatus.APPROVED, views, now, false);
    }

    @Override
    @Transactional
    public void revoke(String masterNo) {
        Master master = requireMaster(masterNo);
        // 重复撤销视为成功；撤销只改母版状态，既有回执与其快照原样保留。
        if (master.status() == MasterStatus.APPROVED) {
            repository.revokeMaster(master.id(), Instant.now(clock));
        }
    }

    @Override
    @Transactional
    public DeliveryResponse deliver(DeliveryRequest request) {
        Master master = requireMaster(request.masterNo());
        repository.lockFilm(master.filmId());

        DeliveryReceipt existing =
                repository.findDeliveryRequest(master.filmId(), request.requestId()).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.masterNo(), request.masterNo())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "同一 requestId 已用于交付其他母版");
            }
            return toDeliveryResponse(existing, true);
        }

        if (master.status() == MasterStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "MASTER_REVOKED",
                    "母版已撤销，不能发起新的交付: " + master.masterNo());
        }

        List<MasterSegment> segments = repository.findMasterSegments(master.id());
        String snapshotJson = writeSnapshot(segments);
        Instant now = Instant.now(clock);
        String receiptNo = "R-" + master.filmId() + "-" + UUID.randomUUID().toString().replace("-", "");
        try {
            repository.insertReceipt(master.filmId(), receiptNo, master.masterNo(),
                    request.requestId(), snapshotJson, now);
        } catch (DuplicateKeyException e) {
            // 交付重试并发到达：唯一约束拦住第二张回执，重放首张。
            DeliveryReceipt winner =
                    repository.findDeliveryRequest(master.filmId(), request.requestId()).orElseThrow();
            if (!Objects.equals(winner.masterNo(), request.masterNo())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "同一 requestId 已用于交付其他母版");
            }
            return toDeliveryResponse(winner, true);
        }
        return new DeliveryResponse(receiptNo, master.masterNo(), request.requestId(),
                toViews(segments), now, false);
    }

    @Override
    @Transactional(readOnly = true)
    public FilmView getFilm(String filmCode) {
        Film film = requireFilm(filmCode);
        List<MasterView> masterViews = repository.findMasters(film.id()).stream()
                .map(m -> new MasterView(m.masterNo(), m.status(),
                        toViews(repository.findMasterSegments(m.id())), m.createdAt(), m.revokedAt()))
                .toList();
        List<ReceiptView> receiptViews = repository.findReceipts(film.id()).stream()
                .map(r -> new ReceiptView(r.receiptNo(), r.masterNo(), r.requestId(),
                        r.snapshotJson(), r.createdAt()))
                .toList();
        return new FilmView(film.code(), film.plannedSegments(),
                repository.findSegmentChain(film.id()), masterViews, receiptViews);
    }

    private Film requireFilm(String filmCode) {
        return repository.findFilmByCode(filmCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FILM_NOT_FOUND",
                        "影片不存在: " + filmCode));
    }

    private Master requireMaster(String masterNo) {
        return repository.findMasterByNo(masterNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MASTER_NOT_FOUND",
                        "母版不存在: " + masterNo));
    }

    private boolean sameParams(SegmentVersion existing, SegmentSubmitRequest request) {
        return existing.segmentNo() == request.segmentNo()
                && Objects.equals(existing.contentDigest(), request.contentDigest())
                && Objects.equals(existing.basedOnVersion(), request.basedOnVersion());
    }

    private ReviewResponse toReviewResponse(Master master, boolean replayed) {
        List<MasterSegmentView> views = toViews(repository.findMasterSegments(master.id()));
        return new ReviewResponse(master.masterNo(), master.status(), views,
                master.createdAt(), replayed);
    }

    private DeliveryResponse toDeliveryResponse(DeliveryReceipt receipt, boolean replayed) {
        List<MasterSegmentView> views = readSnapshot(receipt.snapshotJson());
        return new DeliveryResponse(receipt.receiptNo(), receipt.masterNo(), receipt.requestId(),
                views, receipt.createdAt(), replayed);
    }

    private List<MasterSegmentView> toViews(List<MasterSegment> segments) {
        return segments.stream()
                .map(s -> new MasterSegmentView(s.segmentNo(), s.versionNo(), s.contentDigest()))
                .toList();
    }

    private String writeSnapshot(List<MasterSegment> segments) {
        try {
            return objectMapper.writeValueAsString(toViews(segments));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("母版快照序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MasterSegmentView> readSnapshot(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, MasterSegmentView.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("交付回执快照反序列化失败", e);
        }
    }
}
