package com.agora.catalog;

import com.agora.catalog.application.*;
import com.agora.catalog.domain.CatalogFailure;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorefrontServiceTests {
    final StorefrontStore store=mock(StorefrontStore.class);
    final PublicShops shops=mock(PublicShops.class);
    final StorefrontService service=new StorefrontService(store,shops,Clock.systemUTC());
    @Test void browseConnectivityFailureReturnsSafe503WithoutCallingMerchant(){
        when(store.candidates(any(),any())).thenThrow(new DataAccessResourceFailureException("private database host/password"));
        assertUnavailable(()->service.browse(StorefrontQuery.parse(Map.of())));
        verifyNoInteractions(shops);
    }
    @Test void detailTransactionConnectionFailureReturnsSafe503WithoutCallingMerchant(){
        when(store.detail(any(),any())).thenThrow(new CannotCreateTransactionException("private JDBC URL"));
        assertUnavailable(()->service.detail(UUID.randomUUID()));
        verifyNoInteractions(shops);
    }
    @Test void programmingSqlFailureIsNotMisclassifiedAsDependencyOutage(){
        var defect=new BadSqlGrammarException("query","private SQL",new java.sql.SQLException("bad column","42703"));
        when(store.candidates(any(),any())).thenThrow(defect);
        when(store.detail(any(),any())).thenThrow(defect);
        assertThatThrownBy(()->service.browse(StorefrontQuery.parse(Map.of()))).isSameAs(defect);
        assertThatThrownBy(()->service.detail(UUID.randomUUID())).isSameAs(defect);
    }
    void assertUnavailable(Runnable action){
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogFailure.class,failure->{
            assertThat(failure.status()).isEqualTo(503);assertThat(failure.code()).isEqualTo("CATALOG_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain("private","JDBC","SQL","password");
        });
    }
}
