package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserRole;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Privileged writes (shop create/update, voucher create) require MERCHANT or ADMIN.
 * Anonymous callers already get 401 from {@link LoginInterceptor}.
 */
public class PrivilegeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isPrivilegedWrite(request)) {
            return true;
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(401);
            return false;
        }
        if (!UserRole.isPrivileged(UserRole.normalize(user.getRole()))) {
            response.setStatus(403);
            return false;
        }
        return true;
    }

    static boolean isPrivilegedWrite(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = normalizeUri(request);
        if ("POST".equalsIgnoreCase(method)) {
            return "/shop".equals(uri)
                    || "/voucher".equals(uri)
                    || "/voucher/seckill".equals(uri);
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return "/shop".equals(uri);
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
