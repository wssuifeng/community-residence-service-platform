package com.community.residence.lease.service;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class LeaseTermRulesTest {
    @Test void twelveMonthsAndThreeMonthFirstPaymentStayIndependent(){
        var start=LocalDate.of(2026,10,15);
        assertThat(LeaseTermRules.end(start,12)).isEqualTo("2027-10-14");
        assertThat(LeaseTermRules.end(start,3)).isEqualTo("2027-01-14");
        assertThat(LeaseTermRules.rent(new BigDecimal("3000"),3).add(new BigDecimal("3000"))).isEqualByComparingTo("12000");
    }
    @Test void monthEndDoesNotDrift(){
        var anchor=LocalDate.of(2027,1,31);
        assertThat(LeaseTermRules.end(anchor,1)).isEqualTo("2027-02-27");
        assertThat(LeaseTermRules.end(anchor,2)).isEqualTo("2027-03-30");
    }
    @Test void renewalSixMonthsIsNotTheFirstOneMonthPayment(){
        var next=LocalDate.of(2027,10,15);
        assertThat(LeaseTermRules.end(next,6)).isEqualTo("2028-04-14");
        assertThat(LeaseTermRules.end(next,1)).isEqualTo("2027-11-14");
    }
    @Test void localClosureHasFiveMinuteChannelBuffer(){
        var now=LocalDateTime.of(2026,10,15,12,0);
        var channel=LeaseTermRules.channelDeadline(now,now.toLocalDate(),true);
        assertThat(channel).isEqualTo("2026-10-16T00:00");
        assertThat(LeaseTermRules.localDeadline(channel)).isEqualTo("2026-10-16T00:05");
    }
    @Test void nonFirstOrderHasTwentyFourHours(){
        var now=LocalDateTime.of(2026,10,15,12,0);
        assertThat(LeaseTermRules.channelDeadline(now,null,false)).isEqualTo(now.plusHours(24));
    }
    @Test void invalidMonthCountsRejected(){
        assertThatThrownBy(()->LeaseTermRules.validate(12,13,1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->LeaseTermRules.validate(12,1,0)).isInstanceOf(RuntimeException.class);
    }
    @Test void firstOrderTooCloseToCutoffCannotBeExtendedByChannelMinimum() {
        var now=LocalDateTime.of(2026,10,15,23,59,30);
        assertThatThrownBy(()->LeaseTermRules.channelDeadline(now,now.toLocalDate(),true)).hasMessageContaining("不足一分钟");
    }
}
