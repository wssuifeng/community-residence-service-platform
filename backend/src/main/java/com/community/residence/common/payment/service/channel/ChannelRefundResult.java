package com.community.residence.common.payment.service.channel;

/**
 * 渠道退款受理结果（R72 押金原路退还）。
 * 渠道业务拒绝（如余额不足、交易不存在）以 success=false + failReason 返回，
 * 由调用方落 FAILED 留痕；网络/凭据异常抛 ChannelCallException，调用方同样落 FAILED。
 */
public record ChannelRefundResult(String channelRefundNo, boolean success, String failReason) {

    /** 渠道受理成功 */
    public static ChannelRefundResult success(String channelRefundNo) {
        return new ChannelRefundResult(channelRefundNo, true, null);
    }

    /** 渠道受理失败（业务拒绝） */
    public static ChannelRefundResult failure(String failReason) {
        return new ChannelRefundResult(null, false, failReason);
    }
}
