package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserRole;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Privileged writes (shop create/update, voucher create) require MERCHANT or ADMIN.
 * Anonymous callers already get 401 from {@link LoginInterceptor}.
 * <p>
 * Matches the Spring handler pattern (or path-within-app with matrix vars stripped),
 * not the raw request URI — so {@code /shop;evil=1} still requires privilege.
 */
public class PrivilegeInterceptor implements HandlerInterceptor {

    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

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
        String path = resolveMappedPath(request);
        if ("POST".equalsIgnoreCase(method)) {
            return "/shop".equals(path)
                    || "/voucher".equals(path)
                    || "/voucher/seckill".equals(path);
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return "/shop".equals(path);
        }
        return false;
    }

    /**
     * Prefer {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} set after handler mapping.
     * Fall back to path-within-application with semicolon/matrix content removed.
     */
    static String resolveMappedPath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return normalizePath(pattern.toString());
        }
        return normalizePath(URL_PATH_HELPER.getPathWithinApplication(request));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
