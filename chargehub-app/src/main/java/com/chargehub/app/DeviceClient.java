package com.chargehub.app;
import com.chargehub.infrastructure.*;
import org.springframework.stereotype.Component;import org.springframework.core.env.Environment;
import java.net.*;import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
@Component
public class DeviceClient {
 private final Hmac hmac;private final String base;
 public DeviceClient(Hmac hmac,Environment env){this.hmac=hmac;base=env.getProperty("chargehub.device-url","http://127.0.0.1:18082");}
 @SuppressWarnings("unchecked") public Map<String,Object> post(String path,Map<String,Object> data){
  HttpURLConnection connection=null;
  try{
   String body=Json.write(data);byte[] raw=body.getBytes(StandardCharsets.UTF_8);
   connection=(HttpURLConnection)URI.create(base+path).toURL().openConnection();connection.setRequestMethod("POST");connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setInstanceFollowRedirects(false);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
   HttpURLConnection active=connection;hmac.headers("POST",path,body,"device").forEach(active::setRequestProperty);connection.setFixedLengthStreamingMode(raw.length);try(OutputStream out=connection.getOutputStream()){out.write(raw);}
   int status=connection.getResponseCode();try(InputStream in=status>=400?connection.getErrorStream():connection.getInputStream()){
    if(in==null)throw new IOException("empty device response");byte[] bytes=in.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IOException("device response size");if(status<200||status>299)throw new IOException("device HTTP "+status);
    Map<String,Object> envelope=Json.read(new String(bytes,StandardCharsets.UTF_8));Object payload=envelope.get("data");return payload instanceof Map<?,?>?(Map<String,Object>)payload:envelope;
   }
  }catch(Exception e){throw new BusinessException(503,"CHANNEL_RESULT_UNKNOWN","设备结果未知");}finally{if(connection!=null)connection.disconnect();}
 }
}
