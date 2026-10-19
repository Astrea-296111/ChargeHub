package com.chargehub.app;
import com.chargehub.infrastructure.*;import com.chargehub.station.StationService;import com.chargehub.benefit.BenefitService;
import org.springframework.stereotype.Component;import org.springframework.context.annotation.Profile;import org.springframework.boot.ApplicationArguments;import org.springframework.boot.ApplicationRunner;import org.springframework.transaction.annotation.Transactional;import org.springframework.core.env.Environment;import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;import java.time.*;import java.sql.Timestamp;
@Component @Profile("dev")
public class DevSeed implements ApplicationRunner {
 private final Db db;private final Environment env;private final PasswordEncoder encoder;private final Clock clock;private final StationService stations;private final BenefitService benefits;
 public DevSeed(Db db,Environment env,PasswordEncoder encoder,Clock clock,StationService stations,BenefitService benefits){this.db=db;this.env=env;this.encoder=encoder;this.clock=clock;this.stations=stations;this.benefits=benefits;}
 @Override @Transactional public void run(ApplicationArguments args){
  // Serialize concurrent API/Worker bootstrap before INSERT IGNORE takes shared row locks.
  if(db.one("SELECT id FROM iam_admin_guard WHERE id=1 FOR UPDATE")==null)throw new IllegalStateException("Bootstrap guard migration is missing");
  String pwd=env.getProperty("CHARGEHUB_DEMO_PASSWORD","");if(pwd.length()<12)throw new IllegalStateException("dev requires CHARGEHUB_DEMO_PASSWORD of at least 12 characters");
  String hash=encoder.encode(pwd);
  for(String[] u:List.of(new String[]{"customer","CUSTOMER"},new String[]{"member","CUSTOMER"},new String[]{"ops","OPS"},new String[]{"finance","FINANCE"},new String[]{"admin","ADMIN"})){
   db.update("INSERT IGNORE INTO iam_user(login_name,password_hash) VALUES(?,?)",u[0],hash);
   Map<String,Object> row=db.one("SELECT id FROM iam_user WHERE login_name=?",u[0]);db.update("INSERT IGNORE INTO iam_user_role(user_id,role_code) VALUES(?,?)",row.get("id"),u[1]);
  }
  String monitor=env.getProperty("CHARGEHUB_MONITOR_PASSWORD","");
  if(!monitor.isBlank()){
   if(monitor.length()<16)throw new IllegalStateException("CHARGEHUB_MONITOR_PASSWORD must contain at least 16 characters");
   db.update("INSERT IGNORE INTO iam_user(login_name,password_hash) VALUES('__monitor__',?)",encoder.encode(monitor));
   long monitorId=Db.num(db.one("SELECT id FROM iam_user WHERE login_name='__monitor__'"),"id");
   db.update("INSERT IGNORE INTO iam_user_role(user_id,role_code) VALUES(?,'OPS')",monitorId);
  }
  db.update("INSERT IGNORE INTO iam_user(login_name,password_hash,enabled) VALUES('__system__','!DISABLED',0)");
  long member=Db.num(db.one("SELECT id FROM iam_user WHERE login_name='member'"),"id");Instant now=clock.instant();
  db.update("INSERT IGNORE INTO membership(user_id,plan_code,valid_from,valid_until) VALUES(?,'DEMO_MONTHLY',?,?)",member,Timestamp.from(now.minusSeconds(86400)),Timestamp.from(now.plusSeconds(366L*86400)));
  for(int i=1;i<=2;i++){
   String code="DEMO-0"+i;
   db.update("INSERT IGNORE INTO station(station_code,name,address) VALUES(?,?,?)",code,i==1?"滨江智慧充电站":"科创园充电站",i==1?"上海市滨江路 · 模拟站点":"上海市科创路 · 模拟站点");
   long station=Db.num(db.one("SELECT id FROM station WHERE station_code=?",code),"id");
   for(int j=1;j<=3;j++)db.update("INSERT IGNORE INTO connector(station_id,device_id,connector_code) VALUES(?,?,?)",station,"sim-device-0"+i,"GUN-0"+j);
   if(db.one("SELECT id FROM tariff_plan WHERE station_id=? AND version_no=1",station)==null){Map<String,Object> t=stations.createTariff(Map.of("stationId",String.valueOf(station),"versionNo",1,"slots",List.of(slot(0,480,50,30),slot(480,1080,80,50),slot(1080,1320,120,60),slot(1320,1440,50,30))));stations.publishTariff(ChargeServiceNumber(t));}
  }
  if(db.one("SELECT id FROM coupon_template WHERE name='体验服务费满5减3'")==null){Map<String,Object> t=benefits.createTemplate(Map.of("name","体验服务费满5减3","claimFrom",now.minusSeconds(86400).toString(),"claimUntil",now.plusSeconds(365L*86400).toString(),"useValidFrom",now.minusSeconds(86400).toString(),"useValidUntil",now.plusSeconds(365L*86400).toString(),"thresholdFen",500,"discountFen",300,"totalCount",100,"scope","ALL_STATIONS"));benefits.publishTemplate(Long.parseLong(String.valueOf(t.getOrDefault("templateId",t.get("id")))));}
 }
 private static long ChargeServiceNumber(Map<String,Object> m){return Long.parseLong(String.valueOf(m.getOrDefault("tariffId",m.get("id"))));}
 private static Map<String,Object> slot(int a,int b,int e,int s){return Map.of("startMinute",a,"endMinute",b,"energyRateFenPerKwh",e,"serviceRateFenPerKwh",s);}
}
