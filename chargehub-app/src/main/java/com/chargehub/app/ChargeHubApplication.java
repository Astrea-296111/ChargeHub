package com.chargehub.app;
import java.time.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages="com.chargehub")
@EnableScheduling
public class ChargeHubApplication {
 public static void main(String[] args){SpringApplication.run(ChargeHubApplication.class,args);}
 @Bean Clock businessClock(Environment env){String fixed=env.getProperty("CHARGEHUB_FIXED_TIME","");if(!fixed.isBlank()){
  if(!env.matchesProfiles("dev","test"))throw new IllegalStateException("Fixed clock requires dev or test profile");
  return Clock.fixed(Instant.parse(fixed),ZoneOffset.UTC);
 }return Clock.systemUTC();}
}
