package com.chargehub.app;

import com.chargehub.infrastructure.*;
import com.chargehub.station.StationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Tomcat/JDK HTTP/BCrypt/HMAC/InnoDB boundary tests, never MockMvc or mocked auth. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=${CHARGEHUB_HTTP_TEST_JDBC_URL}","spring.datasource.username=${CHARGEHUB_HTTP_TEST_DB_USER}","spring.datasource.password=${CHARGEHUB_HTTP_TEST_DB_PASSWORD}",
    "CHARGEHUB_FIXED_TIME=","chargehub.payment-key=http-test-payment-signing-key-123456789","chargehub.device-key=http-test-device-signing-key-123456789","chargehub.control-key=http-test-control-key-123456789","spring.main.banner-mode=off"})
@ActiveProfiles("test") @Import(HttpAcceptanceTest.ClockConfig.class)
@EnabledIfEnvironmentVariable(named="CHARGEHUB_HTTP_TEST_JDBC_URL",matches=".+")
class HttpAcceptanceTest {
    static final Instant NOW=Instant.parse("2026-10-18T07:00:00Z");
    static final String PAYMENT_SECRET="http-test-payment-signing-key-123456789";
    static final String CALLBACK="/integrations/payments/mock/notifications";
    static final String PASSWORD="HttpOnlyTestPassword-4829";
    @Autowired Db db; @Autowired StationService stations; @Autowired PasswordEncoder passwords;
    @Value("${local.server.port}") int port;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Response(int status,Map<String,Object> envelope){
        @SuppressWarnings("unchecked") Map<String,Object> data(){return (Map<String,Object>)envelope.get("data");}
        String code(){return Objects.toString(envelope.get("code"),"");}
    }
    record F(long user,long other,long gun,long alternative,String token,String otherToken){}

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties){
        String url=System.getenv("CHARGEHUB_HTTP_TEST_JDBC_URL");
        String database=url==null?"":url.substring(url.lastIndexOf('/')+1).split("\\?",2)[0];
        if(!database.matches("[A-Za-z0-9_]+_test"))throw new IllegalArgumentException("HTTP acceptance needs a dedicated database ending in _test");
        properties.add("spring.datasource.url",()->url);properties.add("chargehub.redis.enabled",()->false);
        properties.add("management.health.redis.enabled",()->false);properties.add("chargehub.messaging.mode",()->"db");
    }
    F fixture()throws Exception{
        String suffix=UUID.randomUUID().toString();String first="http-a-"+suffix,other="http-b-"+suffix;
        String hash=passwords.encode(PASSWORD);
        assertTrue(hash.startsWith("$2"),"Fixture must use the actual BCrypt encoder");
        long user=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,?)",first,hash);
        long second=db.insert("INSERT INTO iam_user(login_name,password_hash) VALUES(?,?)",other,hash);
        db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES(?,'CUSTOMER'),(?,'CUSTOMER')",user,second);
        long station=db.insert("INSERT INTO station(station_code,name,address) VALUES(?,'HTTP Acceptance','isolated fixture')",suffix);
        long gun=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'http-device',?)",station,"a-"+suffix);
        long alternative=db.insert("INSERT INTO connector(station_id,device_id,connector_code) VALUES(?,'http-device',?)",station,"b-"+suffix);
        var tariff=stations.createTariff(Map.of("stationId",Long.toString(station),"versionNo",1,"slots",List.of(Map.of("startMinute",0,"endMinute",1440,"energyRateFenPerKwh",80,"serviceRateFenPerKwh",50))));stations.publishTariff(Long.parseLong(tariff.get("tariffId").toString()));
        return new F(user,second,gun,alternative,login(first),login(other));
    }
    String login(String username)throws Exception{
        Response response=post("/v1/auth/token",null,Json.write(Map.of("username",username,"password",PASSWORD)),null);
        assertEquals(200,response.status);assertEquals("OK",response.code());assertFalse(response.envelope.toString().contains(PASSWORD));
        return response.data().get("accessToken").toString();
    }
    Map<String,Object> body(F f){return Map.of("connectorId",Long.toString(f.gun),"expectedTariffVersion",1,"useMembership",false);}
    String create(F f)throws Exception{
        Response created=post("/v1/charge-orders",f.token,Json.write(body(f)),UUID.randomUUID().toString());
        assertEquals(201,created.status,created.envelope.toString());return created.data().get("orderNo").toString();
    }
    String payment(F f,String no)throws Exception{
        Response result=post("/v1/charge-orders/"+no+"/payment",f.token,"{}",UUID.randomUUID().toString());
        assertEquals(202,result.status,result.envelope.toString());return result.data().get("payNo").toString();
    }
    Response post(String path,String token,String body,String key)throws Exception{return call("POST",path,token,body,key,Map.of());}
    Response get(String path,String token)throws Exception{return call("GET",path,token,null,null,Map.of());}
    Response call(String method,String path,String token,String body,String key,Map<String,String> headers)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json");
        headers.forEach(builder::header);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var envelope=Json.read(response.body());
        assertNotNull(envelope.get("requestId"),"Every HTTP response must be traceable");
        return new Response(response.statusCode(),envelope);
    }
    long orderCount(F f){return Db.num(db.one("SELECT COUNT(*) n FROM charge_order WHERE user_id=?",f.user),"n");}
    Map<String,Object> callback(String pay,long amount,String currency){return Map.of("channelEventId",UUID.randomUUID().toString(),"type","PAYMENT_SUCCESS","payNo",pay,"providerBizNo","http-provider-"+pay,"amountFen",amount,"currency",currency,"occurredAt",NOW.toString());}
    Map<String,String> signature(String body)throws Exception{
        String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical=String.join("\n","POST",CALLBACK,timestamp,nonce,digest);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(PAYMENT_SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Map.of("X-Key-Id","payment","X-Timestamp",timestamp,"X-Nonce",nonce,"X-Signature",Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    }
    void noStartOrCapture(String no,String pay){
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM device_command WHERE order_no=?",no),"n"));
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_no=?",pay),"n"));
        assertEquals(0,Db.num(db.one("SELECT captured_fen FROM payment_order WHERE pay_no=?",pay),"captured_fen"));
    }

    @Test void malformedWritesAreRejectedBeforeAnyOrderResourceOrOutboxEffect()throws Exception{
        F f=fixture();String valid=Json.write(body(f));
        var unknown=new LinkedHashMap<String,Object>(body(f));unknown.put("prepaidFen",1);
        assertEquals(400,post("/v1/charge-orders",f.token,Json.write(unknown),UUID.randomUUID().toString()).status);
        String floating=valid.replace("\"expectedTariffVersion\":1","\"expectedTariffVersion\":1.1");
        assertNotEquals(valid,floating);assertEquals(400,post("/v1/charge-orders",f.token,floating,UUID.randomUUID().toString()).status);
        String duplicate="{\"connectorId\":\""+f.gun+"\",\"connectorId\":\""+f.alternative+"\",\"expectedTariffVersion\":1,\"useMembership\":false}";
        Response duplicated=post("/v1/charge-orders",f.token,duplicate,UUID.randomUUID().toString());assertEquals(400,duplicated.status);assertEquals("INVALID_JSON",duplicated.code());
        String oversized="{\"padding\":\""+"x".repeat(66000)+"\"}";
        Response tooLarge=post("/v1/charge-orders",f.token,oversized,UUID.randomUUID().toString());assertEquals(413,tooLarge.status);assertEquals("BODY_TOO_LARGE",tooLarge.code());
        assertEquals(400,post("/v1/charge-orders",f.token,valid,null).status);
        assertEquals(400,post("/v1/charge-orders",f.token,"",UUID.randomUUID().toString()).status);
        assertEquals(400,get("/v1/charge-orders/not-a-uuid",f.token).status);
        assertEquals(400,get("/v1/charge-orders?limit=0",f.token).status);
        assertEquals(400,get("/v1/stations?limit=101",f.token).status);
        assertEquals(401,get("/v1/stations",null).status);
        assertEquals(0,orderCount(f));assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.gun),"active_order_no"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",f.user),"active_order_no"));
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM idempotency_record WHERE principal_id=?",f.user),"n"));
    }
    @Test void twentyHttpIdempotencyReplaysReturnIdenticalBusinessDataAndChangedBodyConflicts()throws Exception{
        F f=fixture();String key=UUID.randomUUID().toString(),raw=Json.write(body(f));
        Map<String,Object> saved=null;String no=null;
        for(int i=0;i<20;i++){
            Response result=post("/v1/charge-orders",f.token,raw,key);assertEquals(201,result.status,result.envelope.toString());
            if(saved==null){saved=result.data();no=saved.get("orderNo").toString();}else assertEquals(saved,result.data());
        }
        var changed=new LinkedHashMap<String,Object>(body(f));changed.put("connectorId",Long.toString(f.alternative));
        Response conflict=post("/v1/charge-orders",f.token,Json.write(changed),key);assertEquals(409,conflict.status);assertEquals("IDEMPOTENCY_CONFLICT",conflict.code());
        assertEquals(400,post("/v1/charge-orders",f.token,"",key).status,"A prior key cannot bypass the required DTO body");
        assertEquals(1,orderCount(f));assertEquals(no,Db.str(db.one("SELECT active_order_no FROM iam_user WHERE id=?",f.user),"active_order_no"));
        assertNull(Db.str(db.one("SELECT active_order_no FROM connector WHERE id=?",f.alternative),"active_order_no"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM idempotency_record WHERE principal_id=? AND scope='orders.create'",f.user),"n"));
    }
    @Test void anotherCustomersReadStopFundsAndBillAreInvisibleAndMeterOperationsAreForbidden()throws Exception{
        F f=fixture();String no=create(f),path="/v1/charge-orders/"+no;
        assertEquals(404,get(path,f.otherToken).status);
        assertEquals(404,post(path+"/stop",f.otherToken,"{}",UUID.randomUUID().toString()).status);
        assertEquals(404,get(path+"/funds",f.otherToken).status);assertEquals(404,get(path+"/bill",f.otherToken).status);
        assertEquals(403,get("/v1/admin/charge-orders/"+no+"/meter-review",f.token).status);
        assertEquals(403,post("/v1/admin/charge-orders/"+no+"/meter-review/proposals",f.token,Json.write(Map.of("reason","客户不得选择计量复核证据","selectedDeviceEventIds",List.of("1"))),UUID.randomUUID().toString()).status);
        assertEquals("WAIT_PAY",get(path,f.token).data().get("status"));
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM device_command WHERE order_no=?",no),"n"));
        assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM meter_review_proposal WHERE order_no=?",no),"n"));
    }
    @Test void signedAmountAndCurrencyMismatchesRemainDifferenceFactsWithoutStartingOrCapturing()throws Exception{
        for(boolean wrongCurrency:new boolean[]{false,true}){
            F f=fixture();String no=create(f),pay=payment(f,no);String raw=Json.write(callback(pay,wrongCurrency?10000:9999,wrongCurrency?"USD":"CNY"));
            Response result=call("POST",CALLBACK,null,raw,null,signature(raw));assertEquals(200,result.status,result.envelope.toString());
            assertEquals(Boolean.FALSE,result.data().get("valid"));
            var order=db.one("SELECT financial_status,exception_code FROM charge_order WHERE order_no=?",no);assertEquals("DIFF",Db.str(order,"financial_status"));assertNotNull(Db.str(order,"exception_code"));
            noStartOrCapture(no,pay);
            assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM payment_callback WHERE pay_no=?",pay),"n"));
        }
    }
    @Test void invalidSignatureTamperingAndDuplicateJsonNeverBecomeFinancialSuccess()throws Exception{
        F f=fixture();String no=create(f),pay=payment(f,no),raw=Json.write(callback(pay,10000,"CNY"));
        var invalid=new HashMap<>(signature(raw));invalid.put("X-Signature",Base64.getEncoder().encodeToString(new byte[32]));
        assertEquals(401,call("POST",CALLBACK,null,raw,null,invalid).status);
        String changed=raw.replace("\"amountFen\":10000","\"amountFen\":9999");assertNotEquals(raw,changed);
        assertEquals(401,call("POST",CALLBACK,null,changed,null,signature(raw)).status);
        String duplicate=raw.substring(0,raw.length()-1)+",\"amountFen\":9999}";
        Response duplicated=call("POST",CALLBACK,null,duplicate,null,signature(duplicate));assertEquals(400,duplicated.status);assertEquals("INVALID_JSON",duplicated.code());
        noStartOrCapture(no,pay);assertEquals(0,Db.num(db.one("SELECT COUNT(*) n FROM payment_callback WHERE pay_no=?",pay),"n"));
        assertEquals("WAIT_PAY",get("/v1/charge-orders/"+no,f.token).data().get("status"));
    }
    @Test void validCallbackNonceReplayIsRejectedAndOnlyOneCaptureAndStartIntentExist()throws Exception{
        F f=fixture();String no=create(f),pay=payment(f,no),raw=Json.write(callback(pay,10000,"CNY"));
        var headers=signature(raw);Response first=call("POST",CALLBACK,null,raw,null,headers);assertEquals(200,first.status,first.envelope.toString());
        Response replay=call("POST",CALLBACK,null,raw,null,headers);assertEquals(409,replay.status);assertEquals("REPLAY_DETECTED",replay.code());
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM cash_entry WHERE biz_type='CAPTURE' AND biz_no=?",pay),"n"));
        assertEquals(1,Db.num(db.one("SELECT COUNT(*) n FROM device_command WHERE order_no=? AND command_type='START'",no),"n"));
        assertEquals(10000,Db.num(db.one("SELECT captured_fen FROM payment_order WHERE pay_no=?",pay),"captured_fen"));
    }
    @TestConfiguration static class ClockConfig{@Bean @Primary Clock httpAcceptanceClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
}
