package com.chargehub.payment;

import com.chargehub.infrastructure.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChannelFinancialFactTest {
    Map<String,Object> response(){return new LinkedHashMap<>(Map.of("payNo",UUID.randomUUID().toString(),"providerTradeNo","independent-provider","amountFen",10000,"currency","CNY","status","SUCCESS","occurredAt","2026-10-18T07:00:00Z"));}
    @Test void queryFactProducesStableBusinessEventWithoutClaimingHttpSuccessMeansPaymentSuccess(){var r=response();var fact=ChannelFinancialFact.parse(r,false);assertEquals("PAYMENT_SUCCESS",fact.notification().get("type"));assertEquals(fact.channelEventId(),ChannelFinancialFact.parse(r,false).channelEventId());r.put("status","PENDING");r.remove("occurredAt");assertEquals("CHANNEL_NOT_FINAL",assertThrows(BusinessException.class,()->ChannelFinancialFact.parse(r,false).notification()).code());}
    @Test void fractionalOrStringAmountsCannotBecomeIntegerMoney(){for(Object amount:List.of(10000.1,"10000",-1,0)){var r=response();r.put("amountFen",amount);assertEquals("INVALID_CHANNEL_FACT",assertThrows(BusinessException.class,()->ChannelFinancialFact.parse(r,false)).code());}}
    @Test void missingProviderOrFinalOccurrenceIsNotValidEvidence(){var missingProvider=response();missingProvider.remove("providerTradeNo");assertThrows(BusinessException.class,()->ChannelFinancialFact.parse(missingProvider,false));var missingOccurrence=response();missingOccurrence.remove("occurredAt");assertThrows(BusinessException.class,()->ChannelFinancialFact.parse(missingOccurrence,false));}
}
