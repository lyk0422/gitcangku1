package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 业务接口鉴权过滤器：本题信任本地测试头 X-Actor-Id / X-Role。
 * 仅拦截 /api/** ；头缺失或角色非法直接 401，不进入业务与幂等逻辑。
 */
@Component
public class ActorHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER_ACTOR = "X-Actor-Id";
    public static final String HEADER_ROLE = "X-Role";

    private final ActorContext actorContext;
    private final ObjectMapper objectMapper;

    public ActorHeaderFilter(ActorContext actorContext, ObjectMapper objectMapper) {
        this.actorContext = actorContext;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String actorId = request.getHeader(HEADER_ACTOR);
            String roleHeader = request.getHeader(HEADER_ROLE);
            if (actorId == null || actorId.isBlank() || roleHeader == null || roleHeader.isBlank()) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少 X-Actor-Id 或 X-Role 请求头");
                return;
            }
            if (actorId.trim().length() > 64) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "X-Actor-Id 长度不能超过 64 个字符");
                return;
            }
            final Role role;
            try {
                role = Role.valueOf(roleHeader.trim());
            } catch (IllegalArgumentException e) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "X-Role 仅允许 COORDINATOR/REVIEWER/DATA_COLLECTOR/"
                                + "RANDOMIZATION_CUSTODIAN/SAFETY_REVIEWER");
                return;
            }
            actorContext.set(new Actor(actorId.trim(), role));
            filterChain.doFilter(request, response);
        } finally {
            actorContext.clear();
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", "UNAUTHORIZED", "message", message));
    }
}
