package com.chargehub.app;
import org.springframework.context.annotation.Configuration;import org.springframework.core.env.Environment;
/** V1 ships simulation adapters only; a production adapter is a separate, explicit integration. */
@Configuration
public class RuntimeSafety {
 public RuntimeSafety(Environment env){if(env.matchesProfiles("prod","production"))throw new IllegalStateException("ChargeHub v1 contains simulation adapters only; production profile is intentionally unavailable");}
}
