package com.community.residence.common.lease.service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
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
    /** 新订单共享15分钟渠道窗口；旧单续接读取原快照，不重新计算。 */
    public static LocalDateTime channelDeadline(LocalDateTime createdAt){
        return createdAt.plusMinutes(15);
    }
    public static LocalDateTime localDeadline(LocalDateTime channel){
        return channel.plusSeconds(30);
    }
}
