package com.community.residence.payment.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.payment.dto.UpdateAlipayChannelConfigDTO;
import com.community.residence.payment.service.AlipayChannelConfigService;
import com.community.residence.payment.vo.AlipayChannelConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 独立支付凭据管理；不复用公开全局配置，不提供渠道交易测试。 */
@RestController
@RequestMapping("/api/v1/payment/channel-config/alipay")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "支付渠道配置", description = "仅超级管理员，私钥只写不读，交易保持停用")
public class PaymentChannelConfigController {
    private final AlipayChannelConfigService service;
    @GetMapping @Operation(summary = "读取支付宝配置", description = "仅返回公钥和私钥已配置标志，不含私钥或密文")
    public ApiResponse<AlipayChannelConfigVO> get() { return ApiResponse.success(service.get()); }
    @PutMapping @Operation(summary = "保存支付宝四项配置", description = "APPID + 三份 Key；离线校验，不发起交易、不启用渠道")
    public ApiResponse<AlipayChannelConfigVO> save(@Valid @RequestBody UpdateAlipayChannelConfigDTO dto) { return ApiResponse.success(service.save(dto)); }
}
