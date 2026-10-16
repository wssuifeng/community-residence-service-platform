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
    @Test void firstAndRentChannelDeadlineIsExactlyFifteenMinutes(){
        var now=LocalDateTime.of(2026,10,16,12,0,17);
        assertThat(LeaseTermRules.channelDeadline(now)).isEqualTo(now.plusMinutes(15));
    }
    @Test void localClosureIsFifteenMinutesThirtySecondsAfterCreation(){
        var now=LocalDateTime.of(2026,10,16,12,0,17);
        var channel=LeaseTermRules.channelDeadline(now);
        assertThat(LeaseTermRules.localDeadline(channel)).isEqualTo(now.plusMinutes(15).plusSeconds(30));
    }
    @Test void invalidMonthCountsRejected(){
        assertThatThrownBy(()->LeaseTermRules.validate(12,13,1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->LeaseTermRules.validate(12,1,0)).isInstanceOf(RuntimeException.class);
    }
    @Test void firstOrderNearMidnightStillHasTheFullFifteenMinutes() {
        var now=LocalDateTime.of(2026,10,16,23,59,30);
        var channel=LeaseTermRules.channelDeadline(now);
        assertThat(channel).isEqualTo("2026-10-17T00:14:30");
        assertThat(LeaseTermRules.localDeadline(channel)).isEqualTo("2026-10-17T00:15:00");
    }
}
