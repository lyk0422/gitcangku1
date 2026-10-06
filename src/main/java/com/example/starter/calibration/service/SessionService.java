package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.AppendEntryRequest;
import com.example.starter.calibration.api.dto.CloseSessionRequest;
import com.example.starter.calibration.api.dto.CreateSessionRequest;
import com.example.starter.calibration.api.dto.SessionEntryResponse;
import com.example.starter.calibration.api.dto.SessionResponse;
import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Direction;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.SessionStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.SessionRepository;

/**
 * 迟滞校准会话服务：创建（固定仪器与 HYSTERESIS 证书）、顺序追加（1～20 条，按方向选偏移）、
 * 关闭（冻结全部方向与精确结果）、明细查询。追加与关闭在会话行锁内按期望版本原子生效。
 */
@Service
public class SessionService {

    /** 单会话最大条目数。 */
    static final int MAX_ENTRIES = 20;

    private final SessionRepository sessions;
    private final CertificateRepository certificates;
    private final MeasurementRepository measurements;

    public SessionService(SessionRepository sessions,
                          CertificateRepository certificates,
                          MeasurementRepository measurements) {
        this.sessions = sessions;
        this.certificates = certificates;
        this.measurements = measurements;
    }

    /**
     * 创建会话。证书必须存在、为 HYSTERESIS 类型且未撤销，否则 404/422；
     * 会话键重复返回 409。
     */
    @Transactional
    public SessionResponse create(CreateSessionRequest request) {
        String sessionKey = Inputs.requireText(request.sessionKey(), "sessionKey", 48);
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId", 64);
        if (request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String createdBy = Inputs.requireText(request.createdBy(), "createdBy", 64);
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        Certificate cert = certificates.findByIdForUpdate(request.certificateId())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + request.certificateId()));
        if (!cert.isHysteresis()) {
            throw ApiException.unprocessable("CERT_NOT_HYSTERESIS", "证书不是迟滞类型，不能创建校准会话");
        }
        if (cert.revoked()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED", "证书已撤销，不能创建校准会话");
        }

        CalibrationSession session = new CalibrationSession(0L, sessionKey, instrumentId, cert.id(),
                lower, upper, SessionStatus.OPEN, 0, createdBy, Instant.now(), null);
        try {
            sessions.insert(session);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_SESSION_KEY", "会话键已存在: " + sessionKey);
        }
        CalibrationSession saved = sessions.findByKey(sessionKey).orElseThrow();
        return DtoMapper.toResponse(saved, List.of());
    }

    /**
     * 顺序追加一条原始值。首条必须显式 UP/DOWN；后续按原值比较推导方向，相同继承前方向。
     * 校正值 = 原值×斜率+方向偏移（精确计算，不舍入）；超精度 400、版本冲突 409、
     * 会话已关闭 409、超过 20 条或证书撤销 422。
     */
    @Transactional
    public SessionEntryResponse append(String sessionKey, AppendEntryRequest request) {
        if (request.expectedSessionVersion() == null) {
            throw ApiException.badRequest("expectedSessionVersion 不能为空");
        }
        long expectedVersion = request.expectedSessionVersion();
        BigDecimal raw = Inputs.requireDecimal(request.rawValue(), "rawValue");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy", 64);
        Direction explicit = parseDirection(request.direction());

        CalibrationSession session = sessions.findByKeyForUpdate(sessionKey)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionKey));
        if (session.status() == SessionStatus.CLOSED) {
            throw ApiException.conflict("SESSION_CLOSED", "会话已关闭，不能追加: " + sessionKey);
        }
        if (session.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "会话版本冲突: 期望 " + expectedVersion + "，当前 " + session.version());
        }
        if (session.version() >= MAX_ENTRIES) {
            throw ApiException.unprocessable("SESSION_FULL", "会话条目已达上限 " + MAX_ENTRIES);
        }
        Certificate cert = certificates.findByIdForUpdate(session.certificateId())
                .orElseThrow(() -> ApiException.unprocessable("CERTIFICATE_MISSING", "会话关联的证书不存在"));
        if (cert.revoked()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED", "证书已撤销，不能追加");
        }

        List<Measurement> entries = measurements.findBySessionId(session.id());
        Direction direction;
        if (entries.isEmpty()) {
            if (explicit == null) {
                throw ApiException.badRequest("首条必须显式指定 direction（UP/DOWN）");
            }
            direction = explicit;
        } else {
            if (explicit != null) {
                throw ApiException.badRequest("后续条目不得携带 direction，方向由系统按原值变化推导");
            }
            Measurement prev = entries.get(entries.size() - 1);
            int cmp = raw.compareTo(prev.rawReading());
            direction = cmp > 0 ? Direction.UP : cmp < 0 ? Direction.DOWN : prev.direction();
        }

        BigDecimal offset = direction == Direction.UP ? cert.upOffset() : cert.downOffset();
        BigDecimal corrected = raw.multiply(cert.slope()).add(offset);
        boolean passed = corrected.compareTo(session.lowerLimit()) >= 0
                && corrected.compareTo(session.upperLimit()) <= 0;

        int seq = session.version() + 1;
        Instant now = Instant.now();
        Measurement entry = new Measurement(0L, sessionKey + "#" + seq, session.instrumentId(), now,
                raw, session.lowerLimit(), session.upperLimit(), submittedBy, cert.id(),
                corrected, passed, MeasurementStatus.PENDING, session.id(), seq, direction, now);
        measurements.insert(entry);
        sessions.incrementVersion(session.id());
        return DtoMapper.toEntryResponse(measurements.findByKey(entry.measurementKey()).orElseThrow());
    }

    /**
     * 关闭会话：冻结全部方向与精确结果。版本冲突 409；重复关闭 409；空会话 422。
     */
    @Transactional
    public SessionResponse close(String sessionKey, CloseSessionRequest request) {
        if (request.expectedSessionVersion() == null) {
            throw ApiException.badRequest("expectedSessionVersion 不能为空");
        }
        long expectedVersion = request.expectedSessionVersion();
        Inputs.requireText(request.closedBy(), "closedBy", 64);

        CalibrationSession session = sessions.findByKeyForUpdate(sessionKey)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionKey));
        if (session.status() == SessionStatus.CLOSED) {
            throw ApiException.conflict("SESSION_CLOSED", "会话已关闭: " + sessionKey);
        }
        if (session.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "会话版本冲突: 期望 " + expectedVersion + "，当前 " + session.version());
        }
        if (session.version() < 1) {
            throw ApiException.unprocessable("SESSION_EMPTY", "会话至少需要 1 条条目才能关闭");
        }
        sessions.markClosed(session.id(), Instant.now());
        CalibrationSession saved = sessions.findByKey(sessionKey).orElseThrow();
        return DtoMapper.toResponse(saved, measurements.findBySessionId(saved.id()));
    }

    /**
     * 会话明细：包含全部已追加条目（按序号升序）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SessionResponse detail(String sessionKey) {
        CalibrationSession session = sessions.findByKey(sessionKey)
                .orElseThrow(() -> ApiException.notFound("会话不存在: " + sessionKey));
        return DtoMapper.toResponse(session, measurements.findBySessionId(session.id()));
    }

    private static Direction parseDirection(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Direction.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("direction 必须为 UP 或 DOWN");
        }
    }
}
