package com.example.starter.race.service;

import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordHistoryResponse;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CourseResponse;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.CourseRecordRow;
import com.example.starter.race.persistence.CourseRepository;
import com.example.starter.race.persistence.CourseRow;
import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * {@link CourseService} 的事务实现。
 *
 * <p>并发与一致性要点：
 * <ul>
 *   <li>认定事务先对 course 行 SELECT ... FOR UPDATE，串行化同赛道并发认定；
 *       后在锁内重新读取当前纪录并校验“严格更优”，因此并发下基于已过期当前纪录的
 *       认定必然以422失败而不会覆盖先提交者；</li>
 *   <li>通过后在同一事务内追加历史链节点并原子切换 course.current_record_id 指针，
 *       旧纪录自然留在链中，链只增长不可删改；</li>
 *   <li>recordClaimKey 唯一约束保证同一申请键只成功认定一次，重复申请幂等返回首次结果；
 *       requestId 幂等由 {@link IdempotencyExecutor} 保证，失败不占键；</li>
 *   <li>计时读取自已封榜赛事的只读快照：封榜与快照写入原子提交，认定事务只会读到
 *       已提交的最终计时，不会基于将被修正的计时生效。</li>
 * </ul>
 */
@Service
public class CourseServiceImpl implements CourseService {

    private final CourseRepository courseRepository;
    private final RaceRepository raceRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final Clock clock;

    public CourseServiceImpl(CourseRepository courseRepository, RaceRepository raceRepository,
                             IdempotencyExecutor idempotencyExecutor, Clock clock) {
        this.courseRepository = courseRepository;
        this.raceRepository = raceRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ServiceResult registerCourse(RegisterCourseRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "REGISTER_COURSE",
                IdempotencyExecutor.orderedParams("courseKey", request.courseKey()),
                () -> {
                    long now = clock.millis();
                    try {
                        courseRepository.insertCourse(request.courseKey(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("赛道已存在: " + request.courseKey());
                    }
                    CourseRow course = courseRepository.findCourse(request.courseKey())
                            .orElseThrow();
                    return ServiceResult.created(new CourseResponse(
                            course.courseKey(), course.currentRecordId(), course.createdAt()));
                });
    }

    @Override
    @Transactional
    public ServiceResult claimRecord(String courseKey, ClaimRecordRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "CLAIM_RECORD",
                IdempotencyExecutor.orderedParams(
                        "courseKey", courseKey,
                        "raceId", request.raceId(),
                        "bib", request.bib(),
                        "recordClaimKey", request.recordClaimKey()),
                () -> doClaimRecord(courseKey, request));
    }

    /**
     * 认定主体：在 course 行锁内完成全部重校验与原子切换。
     */
    private ServiceResult doClaimRecord(String courseKey, ClaimRecordRequest request) {
        // 行锁串行化同赛道并发认定；锁内读到的均为已提交的最新状态。
        CourseRow course = courseRepository.findCourseForUpdate(courseKey)
                .orElseThrow(() -> new NotFoundException("赛道不存在: " + courseKey));

        // recordClaimKey 幂等：同键重复申请直接返回首次认定结果。
        var existingClaim = courseRepository.findRecordByClaimKey(request.recordClaimKey());
        if (existingClaim.isPresent()) {
            CourseRecordRow existing = existingClaim.get();
            if (!existing.raceId().equals(request.raceId())
                    || !existing.bib().equals(request.bib())
                    || !existing.courseKey().equals(courseKey)) {
                throw new ConflictException(
                        "recordClaimKey 已用于不同参数的认定申请: " + request.recordClaimKey());
            }
            return ServiceResult.created(ResponseMapper.toRecordResponse(existing));
        }

        RaceRow race = raceRepository.findRace(request.raceId())
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + request.raceId()));
        if (!race.courseKey().equals(courseKey)) {
            throw new BadRequestException("赛事不属于该赛道: " + request.raceId());
        }
        if (race.status() != RaceStatus.SEALED) {
            throw new ConflictException("赛事尚未封榜，不能申请纪录认定: " + request.raceId());
        }
        SnapshotRow snapshot = raceRepository.findSnapshot(request.raceId())
                .orElseThrow(() -> new IllegalStateException(
                        "赛事已封榜但缺少快照: " + request.raceId()));
        SnapshotEntryRow entry = snapshot.entries().stream()
                .filter(e -> e.bib().equals(request.bib()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "选手不在封榜快照中: " + request.bib()));
        if (entry.status() == EntryStatus.DISQUALIFIED) {
            throw new UnprocessableEntityException(
                    "选手已取消资格，不能申请纪录认定: " + request.bib());
        }
        if (entry.status() != EntryStatus.RANKED || entry.totalTimeMs() == null) {
            throw new UnprocessableEntityException(
                    "选手无合法完赛计时，不能申请纪录认定: " + request.bib());
        }
        long finalTimeMs = entry.totalTimeMs();

        CourseRecordRow current = course.currentRecordId() == null
                ? null
                : courseRepository.findRecord(course.currentRecordId()).orElseThrow();
        if (current != null && finalTimeMs >= current.timeMs()) {
            // 未严格优于（含并发下已被更优认定推进的）当前纪录：422 并返回实际当前纪录。
            throw new RecordClaimRejectedException(
                    "计时 " + finalTimeMs + "ms 未严格优于赛道当前纪录 "
                            + current.timeMs() + "ms",
                    ResponseMapper.toRecordResponse(current));
        }

        int seq = current == null ? 1 : current.seq() + 1;
        CourseRecordRow record = new CourseRecordRow(
                UUID.randomUUID().toString(),
                request.recordClaimKey(),
                courseKey,
                seq,
                request.raceId(),
                request.bib(),
                finalTimeMs,
                clock.millis());
        courseRepository.appendRecordAndSwitch(record);
        return ServiceResult.created(ResponseMapper.toRecordResponse(record));
    }

    @Override
    @Transactional(readOnly = true)
    public CourseResponse getCourse(String courseKey) {
        CourseRow course = courseRepository.findCourse(courseKey)
                .orElseThrow(() -> new NotFoundException("赛道不存在: " + courseKey));
        return new CourseResponse(course.courseKey(), course.currentRecordId(),
                course.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public CourseRecordHistoryResponse getRecordHistory(String courseKey) {
        CourseRow course = courseRepository.findCourse(courseKey)
                .orElseThrow(() -> new NotFoundException("赛道不存在: " + courseKey));
        List<CourseRecordResponse> history = courseRepository.findRecordHistory(courseKey)
                .stream()
                .map(ResponseMapper::toRecordResponse)
                .toList();
        CourseRecordResponse current = course.currentRecordId() == null
                ? null
                : ResponseMapper.toRecordResponse(
                        courseRepository.findRecord(course.currentRecordId()).orElseThrow());
        return new CourseRecordHistoryResponse(courseKey, current, history);
    }
}
