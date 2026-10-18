package com.community.residence.common.payment.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.payment.dto.UpdateAlipayChannelConfigDTO;
import com.community.residence.common.payment.service.AlipayChannelConfigService;
import com.community.residence.common.payment.vo.AlipayChannelConfigVO;
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
@Tag(name = "支付渠道配置", description = "仅超级管理员，私钥只写不读，启用由超管控制，保存不发起交易")
public class PaymentChannelConfigController {
    private final AlipayChannelConfigService service;
    @GetMapping @Operation(summary = "读取支付宝配置", description = "仅返回公钥和私钥已配置标志，不含私钥或密文")
    public ApiResponse<AlipayChannelConfigVO> get() { return ApiResponse.success(service.get()); }
    @PutMapping @Operation(summary = "保存支付宝四项配置", description = "APPID + 三份 Key及启用控制；保存仅离线校验，不发起交易")
    public ApiResponse<AlipayChannelConfigVO> save(@Valid @RequestBody UpdateAlipayChannelConfigDTO dto) { return ApiResponse.success(service.save(dto)); }
}
