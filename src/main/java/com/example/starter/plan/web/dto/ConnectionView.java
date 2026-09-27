package com.example.starter.plan.web.dto;

/**
 * 接续关系视图。predecessorVersion 为登记时固化的前车版本。
 */
public record ConnectionView(long id, String predecessorKey, String successorKey,
                             String transferStation, int guaranteeMinutes, boolean crossMidnight,
                             int predecessorVersion, String status) {
}
