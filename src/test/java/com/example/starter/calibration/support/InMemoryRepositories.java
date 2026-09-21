package com.example.starter.calibration.support;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.domain.ReleaseRecord;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.CertificateRepository;
import com.example.starter.calibration.repository.MeasurementRepository;
import com.example.starter.calibration.repository.ReleaseRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单元测试用的内存仓库组合。三个仓库共享一把锁，模拟数据库行锁：
 * 复合操作（如检查重叠后插入、条件状态迁移）在锁内原子完成。
 * 测试可用 {@link #lock()} 将一次完整服务调用串行化，模拟事务提交顺序。
 */
public class InMemoryRepositories {

    private final Object lock = new Object();

    private final InMemoryCertificateRepository certificates = new InMemoryCertificateRepository();
    private final InMemoryMeasurementRepository measurements = new InMemoryMeasurementRepository();
    private final InMemoryReleaseRecordRepository releaseRecords = new InMemoryReleaseRecordRepository();

    public Object lock() {
        return lock;
    }

    public CertificateRepository certificates() {
        return certificates;
    }

    public MeasurementRepository measurements() {
        return measurements;
    }

    public ReleaseRecordRepository releaseRecords() {
        return releaseRecords;
    }

    /**
     * 内存证书仓库：insertIfNoOverlap 在锁内完成“检查重叠 + 插入”，与 JDBC 行锁实现等效。
     */
    public class InMemoryCertificateRepository implements CertificateRepository {

        private final Map<Long, Certificate> store = new LinkedHashMap<>();
        private long sequence = 0;

        @Override
        public Certificate insertIfNoOverlap(Certificate certificate) {
            synchronized (lock) {
                for (Certificate existing : store.values()) {
                    if (!existing.revoked()
                            && existing.instrumentId().equals(certificate.instrumentId())
                            && existing.overlaps(certificate.validFrom(), certificate.validTo())) {
                        throw ApiException.conflict(
                                "CERTIFICATE_INTERVAL_OVERLAP",
                                "与已存在证书 " + existing.id() + " 的有效区间重叠");
                    }
                }
                long id = ++sequence;
                Certificate persisted = new Certificate(
                        id,
                        certificate.instrumentId(),
                        certificate.validFrom(),
                        certificate.validTo(),
                        certificate.coefficientA(),
                        certificate.offsetB(),
                        false,
                        null,
                        certificate.createdAt());
                store.put(id, persisted);
                return persisted;
            }
        }

        @Override
        public Optional<Certificate> findById(long id) {
            synchronized (lock) {
                return Optional.ofNullable(store.get(id));
            }
        }

        @Override
        public Optional<Certificate> findByIdForUpdate(long id) {
            return findById(id);
        }

        @Override
        public boolean revoke(long id, Instant revokedAt) {
            synchronized (lock) {
                Certificate existing = store.get(id);
                if (existing == null || existing.revoked()) {
                    return false;
                }
                store.put(id, new Certificate(
                        existing.id(),
                        existing.instrumentId(),
                        existing.validFrom(),
                        existing.validTo(),
                        existing.coefficientA(),
                        existing.offsetB(),
                        true,
                        revokedAt,
                        existing.createdAt()));
                return true;
            }
        }

        @Override
        public Optional<Certificate> findActiveCovering(String instrumentId, Instant instant) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(c -> !c.revoked()
                                && c.instrumentId().equals(instrumentId)
                                && c.covers(instant))
                        .findFirst();
            }
        }

        @Override
        public List<Certificate> findByInstrument(String instrumentId) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(c -> c.instrumentId().equals(instrumentId))
                        .toList();
            }
        }
    }

    /**
     * 内存测量仓库：markReleased 为条件更新，measurementKey 唯一。
     */
    public class InMemoryMeasurementRepository implements MeasurementRepository {

        private final Map<Long, Measurement> store = new LinkedHashMap<>();
        private long sequence = 0;

        @Override
        public Measurement insert(Measurement measurement) {
            synchronized (lock) {
                boolean duplicate = store.values().stream()
                        .anyMatch(m -> m.measurementKey().equals(measurement.measurementKey()));
                if (duplicate) {
                    throw ApiException.conflict(
                            "MEASUREMENT_KEY_DUPLICATE",
                            "measurementKey 已存在: " + measurement.measurementKey());
                }
                long id = ++sequence;
                Measurement persisted = new Measurement(
                        id,
                        measurement.measurementKey(),
                        measurement.instrumentId(),
                        measurement.measuredAt(),
                        measurement.rawReading(),
                        measurement.lowerLimit(),
                        measurement.upperLimit(),
                        measurement.submittedBy(),
                        measurement.certificateId(),
                        measurement.computedValue(),
                        measurement.displayValue(),
                        measurement.passed(),
                        measurement.status(),
                        null,
                        null,
                        measurement.createdAt());
                store.put(id, persisted);
                return persisted;
            }
        }

        @Override
        public Optional<Measurement> findById(long id) {
            synchronized (lock) {
                return Optional.ofNullable(store.get(id));
            }
        }

        @Override
        public Optional<Measurement> findByIdForUpdate(long id) {
            return findById(id);
        }

        @Override
        public Optional<Measurement> findByMeasurementKey(String measurementKey) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(m -> m.measurementKey().equals(measurementKey))
                        .findFirst();
            }
        }

        @Override
        public boolean markReleased(long id, String releasedBy, Instant releasedAt) {
            synchronized (lock) {
                Measurement existing = store.get(id);
                if (existing == null || existing.status() != MeasurementStatus.PENDING_RELEASE) {
                    return false;
                }
                store.put(id, new Measurement(
                        existing.id(),
                        existing.measurementKey(),
                        existing.instrumentId(),
                        existing.measuredAt(),
                        existing.rawReading(),
                        existing.lowerLimit(),
                        existing.upperLimit(),
                        existing.submittedBy(),
                        existing.certificateId(),
                        existing.computedValue(),
                        existing.displayValue(),
                        existing.passed(),
                        MeasurementStatus.RELEASED,
                        releasedBy,
                        releasedAt,
                        existing.createdAt()));
                return true;
            }
        }

        @Override
        public List<Measurement> findByIds(Collection<Long> ids) {
            synchronized (lock) {
                List<Measurement> result = new ArrayList<>();
                for (Long id : ids) {
                    Measurement measurement = store.get(id);
                    if (measurement != null) {
                        result.add(measurement);
                    }
                }
                return result;
            }
        }

        @Override
        public List<Measurement> findCurrentUsable(String instrumentId) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(m -> m.status() == MeasurementStatus.RELEASED)
                        .filter(m -> instrumentId == null || m.instrumentId().equals(instrumentId))
                        .filter(m -> certificates.findById(m.certificateId())
                                .map(c -> !c.revoked())
                                .orElse(false))
                        .toList();
            }
        }
    }

    /**
     * 内存放行历史仓库。
     */
    public class InMemoryReleaseRecordRepository implements ReleaseRecordRepository {

        private final Map<Long, ReleaseRecord> store = new LinkedHashMap<>();
        private long sequence = 0;

        @Override
        public List<ReleaseRecord> insertAll(List<ReleaseRecord> records) {
            synchronized (lock) {
                List<ReleaseRecord> persisted = new ArrayList<>(records.size());
                for (ReleaseRecord record : records) {
                    long id = ++sequence;
                    ReleaseRecord stored = new ReleaseRecord(
                            id,
                            record.measurementId(),
                            record.certificateId(),
                            record.releasedBy(),
                            record.releasedAt(),
                            record.batchId());
                    store.put(id, stored);
                    persisted.add(stored);
                }
                return persisted;
            }
        }

        @Override
        public Optional<ReleaseRecord> findByMeasurementId(long measurementId) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(r -> r.measurementId() == measurementId)
                        .findFirst();
            }
        }

        @Override
        public List<ReleaseRecord> findByMeasurementIds(Collection<Long> measurementIds) {
            synchronized (lock) {
                return store.values().stream()
                        .filter(r -> measurementIds.contains(r.measurementId()))
                        .toList();
            }
        }
    }
}
