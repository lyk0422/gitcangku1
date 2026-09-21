package com.example.starter.support;

import com.example.starter.domain.Asset;
import com.example.starter.domain.Channel;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.domain.RequestRecord;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.DraftRepository;
import com.example.starter.repo.DuplicateKeyException;
import com.example.starter.repo.GrantRepository;
import com.example.starter.repo.PublishedRepository;
import com.example.starter.repo.RequestDedupRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存仓储：与 JDBC 实现遵循同一接口语义，
 * 版本比较替换通过 ConcurrentHashMap.compute 保证原子性。
 */
public final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    public static class Assets implements AssetRepository {
        private final Map<String, Asset> store = new ConcurrentHashMap<>();

        @Override
        public void insert(Asset asset) {
            if (store.putIfAbsent(asset.id(), asset) != null) {
                throw new DuplicateKeyException("素材已存在: " + asset.id());
            }
        }

        @Override
        public Optional<Asset> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    public static class Channels implements ChannelRepository {
        private final Map<String, Channel> store = new ConcurrentHashMap<>();

        @Override
        public void insert(Channel channel) {
            if (store.putIfAbsent(channel.id(), channel) != null) {
                throw new DuplicateKeyException("频道已存在: " + channel.id());
            }
        }

        @Override
        public Optional<Channel> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    public static class Grants implements GrantRepository {
        private final Map<String, Grant> store = new ConcurrentHashMap<>();

        @Override
        public void insert(Grant grant) {
            store.put(grant.id(), grant);
        }

        @Override
        public Optional<Grant> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsCovering(String channelId, String assetId,
                                      Instant start, Instant end) {
            return store.values().stream()
                    .filter(g -> g.channelId().equals(channelId) && g.assetId().equals(assetId))
                    .anyMatch(g -> g.covers(start, end));
        }

        @Override
        public Optional<Grant> markRevoked(String id) {
            return Optional.ofNullable(store.computeIfPresent(id, (k, g) ->
                    new Grant(g.id(), g.channelId(), g.assetId(),
                            g.validFrom(), g.validTo(), true)));
        }
    }

    private record DayKey(String channelId, LocalDate businessDay) {
    }

    public static class Drafts implements DraftRepository {
        private final Map<DayKey, Draft> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Draft> find(String channelId, LocalDate businessDay) {
            return Optional.ofNullable(store.get(new DayKey(channelId, businessDay)));
        }

        @Override
        public boolean replace(String channelId, LocalDate businessDay, long expectedVersion,
                               long newVersion, List<DraftSegment> segments) {
            DayKey key = new DayKey(channelId, businessDay);
            boolean[] replaced = {false};
            store.compute(key, (k, current) -> {
                long currentVersion = current == null ? 0 : current.version();
                if (currentVersion != expectedVersion) {
                    return current;
                }
                replaced[0] = true;
                return new Draft(channelId, businessDay, newVersion, List.copyOf(segments));
            });
            return replaced[0];
        }
    }

    public static class Published implements PublishedRepository {
        private final Map<DayKey, PublishedSchedule> store = new ConcurrentHashMap<>();

        @Override
        public Optional<PublishedSchedule> find(String channelId, LocalDate businessDay) {
            return Optional.ofNullable(store.get(new DayKey(channelId, businessDay)));
        }

        @Override
        public boolean publish(String channelId, LocalDate businessDay, long expectedVersion,
                               long newVersion, long draftVersion, List<DraftSegment> segments) {
            DayKey key = new DayKey(channelId, businessDay);
            boolean[] replaced = {false};
            store.compute(key, (k, current) -> {
                long currentVersion = current == null ? 0 : current.version();
                if (currentVersion != expectedVersion) {
                    return current;
                }
                replaced[0] = true;
                return new PublishedSchedule(channelId, businessDay, newVersion,
                        draftVersion, List.copyOf(segments));
            });
            return replaced[0];
        }
    }

    /**
     * 内存去重仓储：占位记录在结果回填前对并发插入者表现为“行锁等待”，
     * 模拟 MySQL 唯一索引在同事务可见性下的阻塞语义。
     */
    public static class RequestDedup implements RequestDedupRepository {
        private static final long WAIT_TIMEOUT_MS = 10_000;

        private static final class Holder {
            private final String operation;
            private final String fingerprint;
            private volatile String resultJson;

            private Holder(String operation, String fingerprint) {
                this.operation = operation;
                this.fingerprint = fingerprint;
            }
        }

        private final Object lock = new Object();
        private final Map<String, Holder> store = new HashMap<>();

        @Override
        public void insertPlaceholder(String requestId, String operation, String fingerprint) {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
                try {
                    Holder existing;
                    while ((existing = store.get(requestId)) != null
                            && existing.resultJson == null) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            throw new IllegalStateException("等待去重记录完成超时: " + requestId);
                        }
                        lock.wait(remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待去重记录被中断", e);
                }
                if (store.containsKey(requestId)) {
                    throw new DuplicateKeyException("requestId 已存在: " + requestId);
                }
                store.put(requestId, new Holder(operation, fingerprint));
            }
        }

        @Override
        public Optional<RequestRecord> find(String requestId) {
            synchronized (lock) {
                Holder h = store.get(requestId);
                if (h == null) {
                    return Optional.empty();
                }
                return Optional.of(new RequestRecord(requestId, h.operation,
                        h.fingerprint, h.resultJson));
            }
        }

        @Override
        public void complete(String requestId, String resultJson) {
            synchronized (lock) {
                Holder h = store.get(requestId);
                if (h != null) {
                    h.resultJson = resultJson;
                }
                lock.notifyAll();
            }
        }

        @Override
        public void abandon(String requestId) {
            synchronized (lock) {
                store.remove(requestId);
                lock.notifyAll();
            }
        }
    }
}
