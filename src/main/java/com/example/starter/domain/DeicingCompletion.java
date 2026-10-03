package com.example.starter.domain;

import java.time.Instant;

/**
 * 除冰作业完成记录，固化液型与完成时刻。
 * deadlineAt为起飞截止时刻（UTC，不含），一个时隙仅允许一条完成记录。
 */
public record DeicingCompletion(long id, long slotId, FluidType fluidType, Instant completedAt,
        Instant deadlineAt) {
}