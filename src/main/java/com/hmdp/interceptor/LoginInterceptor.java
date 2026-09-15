package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Requires login except for known public endpoints (by method + path).
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isPublic(request)) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    /**
     * Public: user code/login, blog hot, shop-type, shop GET, voucher list GET.
     */
    static boolean isPublic(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = normalizeUri(request);
        if ("POST".equalsIgnoreCase(method)) {
            return "/user/code".equals(uri) || "/user/login".equals(uri);
        }
        if ("GET".equalsIgnoreCase(method)) {
            if ("/blog/hot".equals(uri)) {
                return true;
            }
            if (uri.startsWith("/shop-type")) {
                return true;
            }
            if (uri.startsWith("/shop")) {
                return true;
            }
            if (uri.startsWith("/voucher/list")) {
                return true;
            }
            // Local ops: health/info public; metrics/prometheus for bench scrapes without login.
            // Still same app port — do not publish 8081 publicly. See docs/CURRENT.md.
            if (uri.equals("/actuator/health")
                    || uri.equals("/actuator/info")
                    || uri.equals("/actuator/prometheus")
                    || uri.startsWith("/actuator/metrics")) {
                return true;
            }
            return false;
        }
        return false;
    }

    private static String normalizeUri(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }
}
