package com.agora.catalog;

import com.agora.catalog.application.*;
import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationServiceTests {
    final ReservationStore store=mock(ReservationStore.class);
    final PublicShops shops=mock(PublicShops.class);
    final ReservationService service=new ReservationService(store,shops);
    final Request request=new Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),List.of(new Line(UUID.randomUUID(),UUID.randomUUID(),1)));
    final Reservation released=new Reservation(request.checkoutId(),request.buyerId(),request.shopId(),"RELEASED",Instant.EPOCH,Instant.EPOCH.plusSeconds(900),"EXPIRED",List.of());
    @Test void replayReturnsCurrentStateWithoutMerchant(){
        when(store.replay(request)).thenReturn(Optional.of(released));
        assertThat(service.hold(request)).isEqualTo(released);verifyNoInteractions(shops);verify(store,never()).create(any());
    }
    @Test void merchantFailureRechecksCommittedReplay(){
        when(store.replay(request)).thenReturn(Optional.empty(),Optional.of(released));
        when(shops.find(List.of(request.shopId()))).thenThrow(new CatalogFailure(503,"MERCHANT_UNAVAILABLE","Unavailable"));
        assertThat(service.hold(request)).isEqualTo(released);verify(store,never()).create(any());
    }
    @Test void inactiveShopRechecksReplayThenFailsPrecondition(){
        when(store.replay(request)).thenReturn(Optional.empty());when(shops.find(any())).thenReturn(Map.of());
        assertStatus(409,()->service.hold(request));verify(store,times(2)).replay(request);verify(store,never()).create(any());
    }
    @Test void createsOnlyAfterActiveShopAndStoreRechecksIdentity(){
        when(store.replay(request)).thenReturn(Optional.empty());when(shops.find(List.of(request.shopId()))).thenReturn(Map.of(request.shopId(),new PublicShops.Shop(request.shopId(),"Shop")));
        when(store.create(request)).thenReturn(released);assertThat(service.hold(request)).isEqualTo(released);
        var order=inOrder(store,shops);order.verify(store).replay(request);order.verify(shops).find(List.of(request.shopId()));order.verify(store).create(request);
    }
    @Test void ownerAndPayloadFailurePrecedeMerchant(){
        for(int status:new int[]{404,422}){
            doThrow(new CatalogFailure(status,"REJECTED","Rejected")).when(store).replay(request);
            assertStatus(status,()->service.hold(request));
        }verifyNoInteractions(shops);
    }
    @Test void releaseGetAndWorkerNeedNoMerchant(){
        when(store.get(request.buyerId(),request.checkoutId())).thenReturn(released);when(store.release(request.buyerId(),request.checkoutId())).thenReturn(released);when(store.expire()).thenReturn(2);
        assertThat(service.get(request.buyerId(),request.checkoutId())).isEqualTo(released);
        assertThat(service.release(request.buyerId(),request.checkoutId())).isEqualTo(released);assertThat(service.expire()).isEqualTo(2);verifyNoInteractions(shops);
    }
    @Test void connectionLockAndStatementTimeoutsBecomeSafe503(){
        for(var failure:List.of(new CannotAcquireLockException("private SQL"),new QueryTimeoutException("private SQL"),new DataAccessResourceFailureException("private host"),new CannotCreateTransactionException("private URL"),new org.springframework.jdbc.UncategorizedSQLException("query","private SQL",new java.sql.SQLException("private lock","55P03")),new org.springframework.jdbc.UncategorizedSQLException("query","private SQL",new java.sql.SQLException("private timeout","57014")))){
            doThrow(failure).when(store).replay(request);
            assertThatThrownBy(()->service.hold(request)).isInstanceOfSatisfying(CatalogFailure.class,e->{assertThat(e.status()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("private","SQL","URL");});
        }
    }
    @Test void transactionTimeoutBecomesSafe503ForEveryOperation(){
        var timeout=new org.springframework.transaction.TransactionTimedOutException("private transaction SQL");
        when(store.replay(request)).thenThrow(timeout);when(store.get(any(),any())).thenThrow(timeout);
        when(store.release(any(),any())).thenThrow(timeout);when(store.expire()).thenThrow(timeout);
        for(Runnable operation:List.<Runnable>of(()->service.hold(request),()->service.get(request.buyerId(),request.checkoutId()),
                ()->service.release(request.buyerId(),request.checkoutId()),()->service.expire())){
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(CatalogFailure.class,e->{
                assertThat(e.status()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("private","SQL");
            });
        }
    }
    @Test void sqlProgrammingErrorsAreNotReportedAsOutages(){
        var bug=new BadSqlGrammarException("query","private",new java.sql.SQLException("column","42703"));when(store.replay(request)).thenThrow(bug);
        assertThatThrownBy(()->service.hold(request)).isSameAs(bug);
    }
    void assertStatus(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogFailure.class,e->assertThat(e.status()).isEqualTo(status));}
}
