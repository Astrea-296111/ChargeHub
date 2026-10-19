package com.chargehub.reconcile;

import com.chargehub.identity.UserIdentity;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinanceControllerSecurityTest {
    AnnotationConfigApplicationContext context;FinanceOperationsController controller;
    @BeforeEach void setup(){context=new AnnotationConfigApplicationContext(Config.class);controller=context.getBean(FinanceOperationsController.class);}
    @AfterEach void close(){SecurityContextHolder.clearContext();context.close();}
    @Test void customerAndOpsCannotRecoverMoneyButFinanceAndAdminUseServerIdentity(){
        for(String role:List.of("CUSTOMER","OPS")){authenticate(role);assertThrows(AccessDeniedException.class,()->controller.retry("refund-original","request-key-123",new FinanceOperationsController.Reason("财务复核")));}
        for(String role:List.of("FINANCE","ADMIN")){authenticate(role);var response=controller.retry("refund-original","request-key-123",new FinanceOperationsController.Reason("财务复核"));assertEquals("OK",response.code());assertEquals("42",((Map<?,?>)response.data()).get("operatorId"));}
    }
    @Test void unauthenticatedFinanceOperationIsRejected(){assertThrows(AuthenticationCredentialsNotFoundException.class,()->controller.recoverRefund("original-refund","request-key-123",new FinanceOperationsController.Reason("未认证请求")));}
    @Test void httpBoundaryRejectsBlankReasonBeforeAnyRecoveryWork()throws Exception{
        authenticate("FINANCE");var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        try{var mvc=MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();mvc.perform(post("/v1/admin/refunds/original-refund/retry").header("Idempotency-Key","request-key-123").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());}
        finally{validator.close();}
        verifyNoInteractions(context.getBean(FinanceRecoveryService.class));
    }
    void authenticate(String role){var identity=new UserIdentity(42,"server-finance",Set.of(role));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @org.springframework.boot.test.context.TestConfiguration @EnableMethodSecurity(proxyTargetClass=true) static class Config{
        @Bean FinanceRecoveryService recovery(){var service=mock(FinanceRecoveryService.class);when(service.refund(anyLong(),anyString(),anyString(),anyString(),anyBoolean())).thenAnswer(inv->Map.of("operatorId",inv.getArgument(0),"refundNo",inv.getArgument(1)));return service;}
        @Bean ReconciliationService reconciliation(){return mock(ReconciliationService.class);}
        @Bean FinanceOperationsController controller(FinanceRecoveryService service,ReconciliationService reconciliation){return new FinanceOperationsController(service,reconciliation);}
    }
}
