package com.community.residence.lease.service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
/** 同一日历月锚点用于合同与账期；不依赖当前价格反推历史。 */
public final class LeaseTermRules {
    private LeaseTermRules() {
    }
    public static int months(Integer value,int fallback){
        return value==null?fallback:value;
    }
    public static void validate(int total,int first,int cycle){
        if(total<1||total>1200||first<1||first>total||cycle<1||cycle>total)throw new BusinessException(ErrorCode.INVALID_PARAM,"合同月数、首付月数与缴租周期须为正整数，首付与周期不能超过合同期限");
    }
    public static LocalDate end(LocalDate anchor,int months){
        if(anchor==null||months<1)throw new BusinessException(ErrorCode.INVALID_PARAM,"起租日和月数无效");
        return anchor.plusMonths(months).minusDays(1);
    }
    public static BigDecimal rent(BigDecimal price,int months){
        if(price==null||price.signum()<0||months<1)throw new BusinessException(ErrorCode.INVALID_PARAM,"月租金与支付月数无效");
        return price.multiply(BigDecimal.valueOf(months));
    }
    public static LocalDateTime channelDeadline(LocalDateTime now,LocalDate start,boolean first){
        var deadline=now.plusHours(24);
        if(first&&start!=null){
            var cutoff=start.plusDays(1).atStartOfDay();
            if(cutoff.isBefore(deadline))deadline=cutoff;
        }
        if(deadline.isBefore(now.plusMinutes(1)))throw new BusinessException(ErrorCode.INVALID_PARAM,"距首期付款截止不足一分钟，请勿再发起支付，申请将自动驳回");
        return deadline;
    }
    public static LocalDateTime localDeadline(LocalDateTime channel){
        return channel.plusMinutes(5);
    }
}
