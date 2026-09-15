package com.hmdp.controller;

import com.hmdp.metrics.OpsSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Thin ops snapshot for bench scripts (no Prometheus required).
 * Requires login + ADMIN (see PrivilegeInterceptor).
 */
@RestController
@RequestMapping("/ops")
public class OpsController {

    @Resource
    private OpsSnapshotService opsSnapshotService;

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        return opsSnapshotService.snapshot();
    }
}
