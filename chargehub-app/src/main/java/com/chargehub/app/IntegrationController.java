package com.chargehub.app;

import com.chargehub.charging.*;
import com.chargehub.infrastructure.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Map;

@RestController
public class IntegrationController {
    private final Hmac hmac;
    private final ChargeService orders;
    private final SessionService sessions;
    public IntegrationController(Hmac hmac,ChargeService orders,SessionService sessions){this.hmac=hmac;this.orders=orders;this.sessions=sessions;}
    @PostMapping("/integrations/payments/mock/notifications") public ApiResponse payment(HttpServletRequest request,@RequestBody byte[] raw){hmac.verify(request,raw,"payment");return ApiResponse.ok(orders.notification(decode(raw)));}
    @PostMapping("/integrations/devices/events") public ApiResponse device(HttpServletRequest request,@RequestBody byte[] raw){hmac.verify(request,raw,"device");sessions.ingest(decode(raw));return ApiResponse.ok(Map.of("accepted",true));}
    private Map<String,Object> decode(byte[] raw){try{return Json.read(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString());}catch(CharacterCodingException error){throw new BusinessException(400,"INVALID_JSON","集成正文须为UTF-8");}}
}
