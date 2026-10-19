package com.chargehub.payment;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Typed, validated adapter result. HTTP success alone is never evidence of settled money. */
public record ChannelFinancialFact(String payNo,String refundNo,String providerBizNo,long amountFen,
                                   String currency,String status,Instant occurredAt,String channelEventId,boolean retryable,int attempt) {
    public static ChannelFinancialFact parse(Map<String,Object> response,boolean refund) {
        try {
            String pay=field(response,"payNo","outTradeNo"), number=refund?field(response,"refundNo","outRefundNo"):null;
            if(!UUID.fromString(pay).toString().equals(pay.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            if(refund&&!UUID.fromString(number).toString().equals(number.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            String provider=field(response,"providerBizNo",refund?"providerRefundNo":"providerTradeNo");
            if(provider==null||!provider.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException();
            Object amount=response.get("amountFen");if(!(amount instanceof Number))throw new IllegalArgumentException();
            long fen=new BigDecimal(amount.toString()).longValueExact();if(fen<=0)throw new IllegalArgumentException();
            String currency=Objects.toString(response.get("currency"),"");if(!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException();
            String status=Objects.toString(response.get("status"),"");
            if(!Set.of("PENDING","PROCESSING","SUCCESS","FAILED","CLOSED").contains(status))throw new IllegalArgumentException();
            Instant at=response.get("occurredAt")==null?null:Instant.parse(response.get("occurredAt").toString());
            if(Set.of("SUCCESS","FAILED","CLOSED").contains(status)&&at==null)throw new IllegalArgumentException();
            String id=Objects.toString(response.get("channelEventId"),"");
            if(id.isBlank())id="query-"+Json.hash(pay+"|"+number+"|"+provider+"|"+status+"|"+at).substring(0,64);
            if(id.length()>80)throw new IllegalArgumentException();
            return new ChannelFinancialFact(pay,number,provider,fen,currency,status,at,id,Boolean.TRUE.equals(response.get("retryable")),response.get("attempt") instanceof Number n?n.intValue():0);
        }catch(RuntimeException error){throw new BusinessException(502,"INVALID_CHANNEL_FACT","渠道查单缺少有效的业务号、金额、币种或发生时间");}
    }
    public Map<String,Object> notification(){
        if(!Set.of("SUCCESS","FAILED","CLOSED").contains(status))throw new BusinessException(409,"CHANNEL_NOT_FINAL","渠道结果尚未确定");
        var result=new LinkedHashMap<String,Object>();
        result.put("channelEventId",channelEventId);result.put("type",(refundNo==null?"PAYMENT_":"REFUND_")+status);
        result.put("payNo",payNo);if(refundNo!=null)result.put("refundNo",refundNo);
        result.put("providerBizNo",providerBizNo);result.put("amountFen",amountFen);result.put("currency",currency);result.put("occurredAt",occurredAt.toString());
        if(refundNo!=null&&"FAILED".equals(status)){result.put("retryable",retryable);result.put("attempt",attempt);}
        return result;
    }
    private static String field(Map<String,Object> map,String first,String alternate){return Objects.toString(map.get(first),map.get(alternate)==null?null:map.get(alternate).toString());}
}
