package com.chargehub.app;

import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.BusinessException;
import com.chargehub.station.BillingCalculator;
import com.chargehub.station.StationService;
import com.chargehub.benefit.BenefitService;
import com.chargehub.charging.ChargeService;
import com.chargehub.payment.PaymentService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read models never infer financial closure from the service state. */
@Service
public class ChargingQueryService {
    private final Db db;
    private final StationService stations;
    private final BenefitService benefits;
    private final PaymentService payments;
    private final ChargeService orders;
    private final Clock clock;
    public ChargingQueryService(Db db,StationService stations,BenefitService benefits,PaymentService payments,ChargeService orders,Clock clock){
        this.db=db;this.stations=stations;this.benefits=benefits;this.payments=payments;this.orders=orders;this.clock=clock;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> quote(long user,ChargingRequests.Quote request){
        long connector=parseId(request.connectorId());
        Long coupon=request.couponId()==null?null:parseId(request.couponId());
        Map<String,Object> tariff=stations.tariff(connector,null);
        Map<String,Object> benefit=benefits.preview(user,request.useMembership(),coupon);
        Instant now=clock.instant();ZonedDateTime local=now.atZone(ZoneId.of(tariff.get("timezone").toString()));
        int minute=local.getHour()*60+local.getMinute();
        Map<String,Object> slot=((List<Map<String,Object>>)tariff.get("slots")).stream()
                .filter(s->minute>=Db.num(s,"startMinute")&&minute<Db.num(s,"endMinute")).findFirst()
                .orElseThrow(()->new BusinessException(409,"TARIFF_INVALID","费率时段不完整"));
        var amount=BillingCalculator.calculate(List.of(new BillingCalculator.Segment(request.estimatedEnergyWh(),(int)Db.num(slot,"energyRateFenPerKwh"),(int)Db.num(slot,"serviceRateFenPerKwh"))),Boolean.TRUE.equals(benefit.get("member")),nullableLong(benefit.get("couponThresholdFen")),nullableLong(benefit.get("couponDiscountFen")),10000);
        return Map.of("estimated",true,"tariffVersion",tariff.get("tariffVersion"),"prepaidFen",10000,"amounts",amount.amounts(),"expiresAt",now.plusSeconds(60).toString());
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> funds(long user,String orderNo){
        Map<String,Object> order=orders.owned(user,orderNo);
        Map<String,Object> raw=payments.funds(orderNo);
        Map<String,Object> payment=(Map<String,Object>)raw.get("payment");
        Map<String,Object> bill=db.one("SELECT payable_fen FROM charge_bill WHERE order_no=?",orderNo);
        long captured=Db.num(payment,"captured_fen"),refunded=Db.num(payment,"refund_success_fen");
        Map<String,Object> result=new LinkedHashMap<>();result.put("orderNo",orderNo);result.put("capturedFen",captured);
        result.put("refundReservedFen",Db.num(payment,"refund_reserved_fen"));result.put("refundSuccessFen",refunded);
        result.put("netReceivedFen",captured-refunded);result.put("shortfallFen",Math.max(0,Db.num(bill,"payable_fen")-captured));
        result.put("financialStatus",order.get("financial_status"));
        result.put("refunds",((List<Map<String,Object>>)raw.get("refunds")).stream().map(r->Map.of("refundNo",r.get("refundNo"),"status",r.get("status"),"amountFen",r.get("amountFen"),"reason",r.get("reason"))).toList());return result;
    }
    public static long parseId(String value){try{return Long.parseLong(value);}catch(NumberFormatException e){throw new BusinessException(400,"VALIDATION_ERROR","编号超出64位范围");}}
    private static Long nullableLong(Object value){return value==null?null:((Number)value).longValue();}
}
