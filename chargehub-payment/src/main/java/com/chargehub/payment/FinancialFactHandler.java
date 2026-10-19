package com.chargehub.payment;

import java.util.Map;

/** Application boundary: verified financial recovery must also reconcile the order lifecycle. */
public interface FinancialFactHandler {
    Map<String,Object> acceptVerified(Map<String,Object> event);
    void financialDifference(String orderNo, String code);
    void reconsiderFinancialClose(String orderNo);
}
