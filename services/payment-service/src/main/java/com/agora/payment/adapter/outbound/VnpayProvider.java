package com.agora.payment.adapter.outbound;
import com.agora.payment.application.PaymentProvider;
import com.agora.payment.domain.Payment;
import com.agora.payment.domain.Payment.*;
import com.agora.payment.application.ProviderJobs.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class VnpayProvider implements PaymentProvider {
    private final String merchant,secret,returnUrl;
    private final VnpayApiClient api;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    public VnpayProvider(@Value("${agora.vnpay.tmn-code:}") String merchant,@Value("${agora.vnpay.hash-secret:}") String secret,@Value("${agora.vnpay.return-url}") String returnUrl,VnpayApiClient api){this.api=api;this.merchant=merchant;this.secret=secret;this.returnUrl=returnUrl;}
    @Override public Authenticated authenticate(String raw){
        VnpayCodec codec;try{codec=new VnpayCodec(merchant,secret,URI.create(returnUrl));}catch(IllegalArgumentException e){throw Payment.unavailable();}
        var ref=codec.authenticate(raw);
        return new Authenticated(){
            public String txnRef(){return ref.txnRef();}
            public Notice bind(long amount){var n=ref.bind(new VnpayCodec.Expected(ref.txnRef(),amount));return new Notice(n.transactionNo(),n.responseCode(),n.transactionStatus(),n.outcome().name());}
            public void bindReturn(long amount){ref.bindReturn(new VnpayCodec.Expected(ref.txnRef(),amount));}
        };
    }
    @Override public ProviderResult query(QueryWork work){
        var codec=new VnpayApiCodec(merchant,secret);var expected=new VnpayApiCodec.Expected(work.target().txnRef(),work.target().amountVnd());
        var request=codec.query(work.requestId().toString().replace("-",""),expected,DATE.format(work.originalCreated()),DATE.format(work.requestedAt()),"127.0.0.1","Query payment "+work.target().id());
        var result=codec.verifyQuery(api.send(request),expected);return new ProviderResult(result.transactionNo(),result.status(),result.outcome()==VnpayApiCodec.Outcome.SUCCEEDED);
    }
    @Override public ProviderResult refund(RefundWork work){
        var codec=new VnpayApiCodec(merchant,secret);var expected=new VnpayApiCodec.Expected(work.txnRef(),work.capture().amountVnd());
        if(work.providerTransactionNo()!=null){
            var query=codec.query(java.util.UUID.randomUUID().toString().replace("-",""),expected,DATE.format(work.originalCreated()),DATE.format(java.time.Instant.now()),"127.0.0.1","Query refund "+work.requestId());
            var parameters=new java.util.HashMap<>(query.params());parameters.put("vnp_TransactionNo",work.providerTransactionNo());
            var result=codec.verifyRefundQuery(api.send(new VnpayApiCodec.Request(parameters)),expected,work.providerTransactionNo());
            return new ProviderResult(result.transactionNo(),result.status(),result.outcome()==VnpayApiCodec.Outcome.SUCCEEDED);
        }
        var request=codec.refund(work.requestId().toString().replace("-",""),expected,work.capture().providerTransactionNo(),DATE.format(work.originalCreated()),"agora",DATE.format(work.requestedAt()),"127.0.0.1","Refund "+work.requestId());
        var response=api.send(request);var result=codec.verifyRefund(response,expected);
        if(!request.params().get("vnp_OrderInfo").equals(response.get("vnp_OrderInfo")))return ProviderResult.unknown();
        return new ProviderResult(result.transactionNo(),result.status(),result.outcome()==VnpayApiCodec.Outcome.SUCCEEDED);
    }

}
