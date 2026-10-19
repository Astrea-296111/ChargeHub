package com.chargehub.charging;

import com.chargehub.infrastructure.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Stable seek cursor for the (created_at,id) index, never an offset. */
public record OrderCursor(Instant createdAt,long id) {
    public String encode(){return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt+"|"+id).getBytes(StandardCharsets.US_ASCII));}
    public static OrderCursor decode(String encoded){
        try{
            if(encoded==null||encoded.isBlank()||encoded.length()>128)throw new IllegalArgumentException();
            String decoded=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.US_ASCII);
            String[] values=decoded.split("\\|",-1);
            if(values.length!=2)throw new IllegalArgumentException();
            Instant at=Instant.parse(values[0]);long id=Long.parseLong(values[1]);
            if(id<=0||at.getNano()%1_000_000!=0)throw new IllegalArgumentException();
            return new OrderCursor(at,id);
        }catch(Exception error){throw new BusinessException(400,"INVALID_CURSOR","订单游标不正确");}
    }
}
