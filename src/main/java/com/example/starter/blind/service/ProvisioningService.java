package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.CenterSequenceRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ProtocolSeatRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 协议席位池与中心盲码序列供给：
 * 比例按最大公约数规范化为最小区组（如 30:70 → 每区组 3A7B 共 10 席，50:50 → 每区组 2A2B 共 4 席）；
 * 修订生效、中心激活与暂停恢复时，在调用方事务内补足席位池并预留中心独立盲码序列。
 * 任一供给失败抛出 422，由外层事务整体回滚，不留下半成品。
 */
@Component
public class ProvisioningService {

    /** 盲码单条插入冲突后的最大重试次数。 */
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final ProtocolSeatRepository protocolSeatRepository;
    private final CenterSequenceRepository centerSequenceRepository;
    private final AllocationRepository allocationRepository;
    private final ExperimentRepository experimentRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final JdbcTemplate jdbc;

    public ProvisioningService(ProtocolSeatRepository protocolSeatRepository,
                               CenterSequenceRepository centerSequenceRepository,
                               AllocationRepository allocationRepository,
                               ExperimentRepository experimentRepository,
                               BlindCodeGenerator blindCodeGenerator,
                               JdbcTemplate jdbc) {
        this.protocolSeatRepository = protocolSeatRepository;
        this.centerSequenceRepository = centerSequenceRepository;
        this.allocationRepository = allocationRepository;
        this.experimentRepository = experimentRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.jdbc = jdbc;
    }

    /** 规范化比例结果：每区组席位数与其中 A/B 席位数。 */
    public record NormalizedBlock(int seatsPerBlock, int aPerBlock, int bPerBlock) {
    }

    /** 比例规范化：按 gcd(ratioA,100) 归约为最小区组，比例语义不变。 */
    public NormalizedBlock normalize(int ratioA, int ratioB) {
        int gcd = gcd(ratioA, ratioA + ratioB);
        int seatsPerBlock = (ratioA + ratioB) / gcd;
        return new NormalizedBlock(seatsPerBlock, ratioA / gcd, ratioB / gcd);
    }

    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * 补足某协议版本的中心席位池，使空闲席位不少于 neededFree。
     * 中心席位统一存于 protocol_seat（V1 中心池编号从固定 V1 全局区组之后开始，避免与全局席位冲突）；
     * 已占用席位只统计该版本的中心登记，不含旧的无中心全局登记。
     */
    public void ensureSeatCapacity(String experimentId, int version, int ratioA, int ratioB,
                                   long neededFree) {
        if (neededFree <= 0) {
            return;
        }
        NormalizedBlock block = normalize(ratioA, ratioB);
        long existingSeats = countProtocolSeats(experimentId, version);
        long occupied = allocationRepository.countCenterByVersion(experimentId, version);
        long free = existingSeats - occupied;
        int nextBlockNo = nextBlockNo(experimentId, version);
        while (free < neededFree) {
            for (int seatNo = 1; seatNo <= block.seatsPerBlock(); seatNo++) {
                String treatment = seatNo <= block.aPerBlock() ? "A" : "B";
                protocolSeatRepository.insert(experimentId, version, nextBlockNo, seatNo, treatment);
            }
            free += block.seatsPerBlock();
            nextBlockNo++;
        }
    }

    private int nextBlockNo(String experimentId, int version) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(block_no) FROM protocol_seat WHERE experiment_id = ? AND version = ?",
                Integer.class, experimentId, version);
        if (max != null) {
            return max + 1;
        }
        if (version == 1) {
            // V1 中心池避开实验创建时固定的全局 V1 区组编号 1..blockCount。
            return experimentRepository.findById(experimentId).blockCount() + 1;
        }
        return 1;
    }

    private long countProtocolSeats(String experimentId, int version) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_seat WHERE experiment_id = ? AND version = ?",
                Long.class, experimentId, version);
        return count == null ? 0 : count;
    }

    /**
     * 为中心在某协议版本预留 count 条独立盲码序列，顺序编号；盲码全局唯一冲突时重试，
     * 超过重试上限抛出 422（序列生成失败）。
     */
    public void generateSequences(String experimentId, String centerId, int version, long count) {
        long existing = centerSequenceRepository.countTotal(experimentId, centerId, version);
        for (long i = 0; i < count; i++) {
            int seqNo = (int) (existing + i + 1);
            insertOneWithRetry(experimentId, centerId, version, seqNo);
        }
    }

    private void insertOneWithRetry(String experimentId, String centerId, int version, int seqNo) {
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            try {
                centerSequenceRepository.insert(experimentId, centerId, version, seqNo,
                        blindCodeGenerator.nextCode());
                return;
            } catch (DuplicateKeyException e) {
                last = e;
            }
        }
        throw ApiException.unprocessable("中心 " + centerId + " 盲码序列生成失败");
    }
}
