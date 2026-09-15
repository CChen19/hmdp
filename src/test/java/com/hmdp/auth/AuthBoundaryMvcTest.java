package com.hmdp.auth;

import com.hmdp.controller.ShopController;
import com.hmdp.controller.VoucherController;
import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.PrivilegeInterceptor;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.trade.TradeOrderService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for auth path classes (401 / 403 / privileged OK). No MySQL/Redis.
 */
@ExtendWith(MockitoExtension.class)
class AuthBoundaryMvcTest {

    @Mock
    private IShopService shopService;
    @Mock
    private IVoucherService voucherService;
    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private TradeOrderService tradeOrderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopController shopController = new ShopController();
        shopController.shopService = shopService;
        VoucherController voucherController = new VoucherController();
        ReflectionTestUtils.setField(voucherController, "voucherService", voucherService);
        VoucherOrderController orderController = new VoucherOrderController();
        ReflectionTestUtils.setField(orderController, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(orderController, "tradeOrderService", tradeOrderService);

        Filter testAuthFilter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws java.io.IOException, javax.servlet.ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                String role = req.getHeader("X-Test-Role");
                if (role != null) {
                    UserDTO user = new UserDTO();
                    user.setId(1L);
                    user.setNickName("tester");
                    user.setRole(role);
                    UserHolder.saveUser(user);
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    UserHolder.removeUser();
                }
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(shopController, voucherController, orderController)
                .addFilters(testAuthFilter)
                .addInterceptors(new LoginInterceptor(), new PrivilegeInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousPutShop_returns401() throws Exception {
        mockMvc.perform(put("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        verify(shopService, never()).update(any(Shop.class));
    }

    @Test
    void anonymousPostShop_returns401() throws Exception {
        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        verify(shopService, never()).save(any(Shop.class));
    }

    @Test
    void anonymousPostVoucher_returns401() throws Exception {
        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"v\"}"))
                .andExpect(status().isUnauthorized());
        verify(voucherService, never()).save(any(Voucher.class));
    }

    @Test
    void userRolePostShop_returns403() throws Exception {
        mockMvc.perform(post("/shop")
                        .header("X-Test-Role", UserRole.USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(shopService, never()).save(any(Shop.class));
    }

    @Test
    void userRolePostShopWithMatrixVar_returns403() throws Exception {
        // Raw URI /shop;evil=1 must still hit privilege gate (not skip via equals on requestURI)
        mockMvc.perform(post("/shop;evil=1")
                        .header("X-Test-Role", UserRole.USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(shopService, never()).save(any(Shop.class));
    }

    @Test
    void userRolePostVoucher_returns403() throws Exception {
        mockMvc.perform(post("/voucher")
                        .header("X-Test-Role", UserRole.USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"v\"}"))
                .andExpect(status().isForbidden());
        verify(voucherService, never()).save(any(Voucher.class));
    }

    @Test
    void merchantPostShop_ok() throws Exception {
        when(shopService.save(any(Shop.class))).thenReturn(true);
        mockMvc.perform(post("/shop")
                        .header("X-Test-Role", UserRole.MERCHANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isOk());
        verify(shopService).save(any(Shop.class));
    }

    @Test
    void adminPostVoucher_ok() throws Exception {
        when(voucherService.save(any(Voucher.class))).thenReturn(true);
        mockMvc.perform(post("/voucher")
                        .header("X-Test-Role", UserRole.ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"v\"}"))
                .andExpect(status().isOk());
        verify(voucherService).save(any(Voucher.class));
    }

    @Test
    void adminPostSeckillVoucher_ok() throws Exception {
        mockMvc.perform(post("/voucher/seckill")
                        .header("X-Test-Role", UserRole.ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"s\",\"stock\":1}"))
                .andExpect(status().isOk());
        verify(voucherService).addSeckillVoucher(any(Voucher.class));
    }

    @Test
    void userRolePostRedeem_returns403() throws Exception {
        mockMvc.perform(post("/voucher-order/1/redeem")
                        .param("shopId", "10")
                        .header("X-Test-Role", UserRole.USER))
                .andExpect(status().isForbidden());
        verify(tradeOrderService, never()).redeemOrder(any(), any());
    }

    @Test
    void merchantPostRedeem_ok() throws Exception {
        when(tradeOrderService.redeemOrder(1L, 10L)).thenReturn(Result.ok());
        mockMvc.perform(post("/voucher-order/1/redeem")
                        .param("shopId", "10")
                        .header("X-Test-Role", UserRole.MERCHANT))
                .andExpect(status().isOk());
        verify(tradeOrderService).redeemOrder(1L, 10L);
    }
}
