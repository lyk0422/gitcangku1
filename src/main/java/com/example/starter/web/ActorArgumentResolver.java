package com.example.starter.web;

import com.example.starter.domain.Actor;
import com.example.starter.error.BadRequestException;
import com.example.starter.error.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 从受信任的本地测试头 X-Actor-Id / X-Role 解析操作者身份。
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String actorId = request == null ? null : request.getHeader("X-Actor-Id");
        String roleHeader = request == null ? null : request.getHeader("X-Role");
        if (actorId == null || actorId.isBlank()) {
            throw new UnauthorizedException("missing X-Actor-Id header");
        }
        if (roleHeader == null || roleHeader.isBlank()) {
            throw new UnauthorizedException("missing X-Role header");
        }
        final Actor.Role role;
        try {
            role = Actor.Role.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("unsupported X-Role: " + roleHeader);
        }
        return new Actor(actorId.trim(), role);
    }

    /** 注册操作者参数解析器。 */
    @Configuration
    public static class WebConfig implements WebMvcConfigurer {
        private final ActorArgumentResolver resolver;

        public WebConfig(ActorArgumentResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(resolver);
        }
    }
}
