package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.AppendEntryRequest;
import com.example.starter.calibration.api.dto.CloseSessionRequest;
import com.example.starter.calibration.api.dto.CreateSessionRequest;
import com.example.starter.calibration.api.dto.SessionResponse;
import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.CertType;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Direction;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.SessionEntry;
import com.example.starter.calibration.model.SessionStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.SessionEntryRepository;
import com.example.starter.calibration.repo.SessionRepository;

/**
 * 迟滞校准会话服务：创建（固定仪器与 HYSTERESIS 证书）、顺序追加（方向推导、版本控制）、
 * 关闭冻结、明细查询。追加与关闭通过会话行锁串行化；全部写操作按 requestId 幂等。
 */
@Service
public class SessionService {

    /** 单会话最大条目数。 */
    static final int MAX_ENTRIES = 20;

    private final SessionRepository sessions;
    private final SessionEntryRepository entries;
    private final CertificateRepository certificates;
    private final MeasurementRepository measurements;
    private final IdempotencyService idempotency;

    public SessionService(SessionRepository sessions, SessionEntryRepository entries,
                          CertificateRepository certificates, MeasurementRepository measurements,
                          IdempotencyService idempotency) {
        this.sessions = sessions;
        this.entries = entries;
        this.certificates = certificates;
        this.measurements = measurements;
        this.idempotency = idempotency;
    }

    /**
     * 创建会话：证书须存在、为 HYSTERESIS 类型、未撤销，仪器须一致。
     */
    @Transactional
    public SessionResponse create(CreateSessionRequest request) {
        String requestId = Inputs.requireRequestId(request.requestId());
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        if (request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }
        long certificateId = request.certificateId();

        List<Object> identity = List.of("SESSION_CREATE", instrumentId, certificateId);
        return idempotency.execute(requestId, "SESSION_CREATE", identity, SessionResponse.class, () -> {
            Certificate cert = certificates.findById(certificateId)
                    .orElseThrow(() -> ApiException.notFound("证书不存在: " + certificateId));
            if (cert.certType() != CertType.HYSTERESIS) {
                throw ApiException.unprocessable("CERTIFICATE_TYPE_NOT_SUPPORTED",
                        "线性证书不支持校准会话: " + certificateId);
            }
            if (cert.revoked()) {
                throw ApiException.conflict("CERTIFICATE_REVOKED", "证书已撤销: " + certificateId);
            }
            if (!cert.instrumentId().equals(instrumentId)) {
                throw ApiException.badRequest("instrumentId 与证书仪器不一致");
            }
            long id = sessions.insert(instrumentId, certificateId, Instant.now());
            return toResponse(sessions.findById(id).orElseThrow());
        });
    }

    /**
     * 顺序追加条目。首条显式 UP/DOWN；后续读数增大选 UP、减小选 DOWN、相同继承前方向。
     * 校正值=原值×斜率+方向偏移；期望版本须等于当前版本，成功后加一；关闭后拒绝追加。
     */
    @Transactional
    public SessionResponse append(long sessionId, AppendEntryRequest request) {
        String requestId = Inputs.requireRequestId(request.requestId());
        long expectedVersion = Inputs.requireVersion(request.expectedSessionVersion(), "expectedSessionVersion");
        BigDecimal raw = Inputs.requireDecimal(request.rawValue(), "rawValue");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        List<Object> identity = List.of("SESSION_APPEND", submittedBy, sessionId, expectedVersion,
                raw.toPlainString(), request.direction() == null ? "" : request.direction().trim(),
                lower.toPlainString(), upper.toPlainString());
        return idempotency.execute(requestId, "SESSION_APPEND", identity, SessionResponse.class, () -> {
            CalibrationSession session = sessions.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionId));
            if (session.status() != SessionStatus.OPEN) {
                throw ApiException.conflict("SESSION_CLOSED", "会话已关闭，不允许追加: " + sessionId);
            }
            if (session.version() >= MAX_ENTRIES) {
                throw ApiException.conflict("SESSION_FULL", "会话条目已达上限 " + MAX_ENTRIES + ": " + sessionId);
            }
            if (expectedVersion != session.version()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "期望版本 " + expectedVersion + " 与当前版本 " + session.version() + " 不一致");
            }

            Direction direction = resolveDirection(session, raw, request.direction());
            Certificate cert = certificates.findById(session.certificateId()).orElseThrow();
            BigDecimal offset = direction == Direction.UP ? cert.upOffset() : cert.downOffset();
            BigDecimal corrected = raw.multiply(cert.slope()).add(offset);
            boolean passed = corrected.compareTo(lower) >= 0 && corrected.compareTo(upper) <= 0;

            int seq = session.version() + 1;
            String measurementKey = "SES-" + sessionId + "-" + seq;
            Measurement measurement = new Measurement(
                    0L, measurementKey, session.instrumentId(), null, raw, lower, upper, submittedBy,
                    cert.id(), corrected, passed, MeasurementStatus.PENDING, sessionId, direction.name(),
                    Instant.now());
            long measurementId = measurements.insert(measurement);
            entries.insert(new SessionEntry(0L, sessionId, seq, raw, direction, corrected, lower, upper,
                    passed, submittedBy, measurementId, Instant.now()));
            sessions.updateAfterAppend(sessionId, seq, direction, raw);
            return toResponse(sessions.findById(sessionId).orElseThrow());
        });
    }

    /**
     * 关闭会话：期望版本须等于当前版本；关闭后冻结全部方向与精确结果，不再允许追加。
     */
    @Transactional
    public SessionResponse close(long sessionId, CloseSessionRequest request) {
        String requestId = Inputs.requireRequestId(request.requestId());
        long expectedVersion = Inputs.requireVersion(request.expectedSessionVersion(), "expectedSessionVersion");

        List<Object> identity = List.of("SESSION_CLOSE", sessionId, expectedVersion);
        return idempotency.execute(requestId, "SESSION_CLOSE", identity, SessionResponse.class, () -> {
            CalibrationSession session = sessions.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionId));
            if (session.status() != SessionStatus.OPEN) {
                throw ApiException.conflict("SESSION_CLOSED", "会话已关闭: " + sessionId);
            }
            if (expectedVersion != session.version()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "期望版本 " + expectedVersion + " 与当前版本 " + session.version() + " 不一致");
            }
            sessions.markClosed(sessionId, Instant.now());
            return toResponse(sessions.findById(sessionId).orElseThrow());
        });
    }

    /**
     * 会话明细：含全部条目的方向与未舍入校正值；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SessionResponse get(long sessionId) {
        CalibrationSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionId));
        return toResponse(session);
    }

    /** 首条显式方向；后续按读数增减推导，相同继承前方向（仅会话内，历史方向不受新会话影响）。 */
    private Direction resolveDirection(CalibrationSession session, BigDecimal raw, String explicit) {
        if (session.version() == 0) {
            String value = explicit == null ? "" : explicit.trim();
            try {
                return Direction.valueOf(value);
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("首条必须显式给出 direction（UP 或 DOWN）");
            }
        }
        int cmp = raw.compareTo(session.lastRaw());
        if (cmp > 0) {
            return Direction.UP;
        }
        if (cmp < 0) {
            return Direction.DOWN;
        }
        return session.lastDirection();
    }

    private SessionResponse toResponse(CalibrationSession session) {
        List<SessionEntry> sessionEntries = entries.findBySessionId(session.id());
        Map<Long, String> keys = measurements.findBySessionId(session.id()).stream()
                .collect(Collectors.toMap(Measurement::id, Measurement::measurementKey));
        return DtoMapper.toResponse(session, sessionEntries, keys);
    }
}
