package com.agora.payment.application;
import com.agora.payment.domain.Payment.*;
import com.agora.payment.application.ProviderJobs.*;
public interface PaymentProvider {
    ProviderResult query(QueryWork work);
    ProviderResult refund(RefundWork work);
    Authenticated authenticate(String rawQuery);
    interface Authenticated {String txnRef();Notice bind(long amountVnd);void bindReturn(long amountVnd);}
}
