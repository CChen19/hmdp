package com.hmdp.auth;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpSession;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_FAIL_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Login captcha consume + logout session delete (mocked Redis).
 */
@ExtendWith(MockitoExtension.class)
class UserLoginLogoutTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private UserMapper userMapper;
    @Mock
    private HttpSession session;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    private void stubValueOps() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void loginConsumesCode_secondLoginWithSameCodeFails() {
        stubValueOps();
        String phone = "13800138000";
        String code = "123456";
        when(valueOps.get(LOGIN_CODE_FAIL_KEY + phone)).thenReturn(null);
        when(valueOps.get(LOGIN_CODE_KEY + phone)).thenReturn(code).thenReturn(null);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.delete(anyString())).thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Boolean.TRUE);
        when(valueOps.increment(LOGIN_CODE_FAIL_KEY + phone)).thenReturn(1L);

        User user = new User();
        user.setId(9L);
        user.setPhone(phone);
        user.setNickName("n");
        user.setRole(UserRole.USER);
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(phone);
        form.setCode(code);

        Result first = userService.login(form, session);
        assertTrue(first.getSuccess());
        verify(stringRedisTemplate).delete(LOGIN_CODE_KEY + phone);

        Result second = userService.login(form, session);
        assertFalse(second.getSuccess());
        assertEquals("验证码错误", second.getErrorMsg());
    }

    @Test
    void logoutDeletesTokenSession() {
        when(stringRedisTemplate.delete(LOGIN_USER_KEY + "tok-abc")).thenReturn(Boolean.TRUE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "tok-abc");
        Result result = userService.logout(request);
        assertTrue(result.getSuccess());
        verify(stringRedisTemplate).delete(LOGIN_USER_KEY + "tok-abc");
    }

    @Test
    void sendCodeRateLimited() {
        stubValueOps();
        String phone = "13900139000";
        when(valueOps.setIfAbsent(eq(RedisConstants.LOGIN_CODE_SEND_LIMIT_KEY + phone),
                eq("1"), eq(RedisConstants.LOGIN_CODE_SEND_LIMIT_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);
        Result result = userService.sendCode(phone, session);
        assertFalse(result.getSuccess());
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
