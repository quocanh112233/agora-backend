package com.agora.commerce;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.CartFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartServiceTests {
    final CartStore store=mock(CartStore.class);final CatalogOffers catalog=mock(CatalogOffers.class);
    final CartService service=new CartService(store,catalog);
    final UUID actor=UUID.randomUUID(),sku=UUID.randomUUID(),product=UUID.randomUUID();
    final CartStore.Key key=new CartStore.Key(actor,UUID.randomUUID(),"PUT_ITEM","hash");
    @Test void concurrentOriginalCommitIsReplayedWhenCatalogLookupFails(){
        when(store.replay(key)).thenReturn(Optional.empty()).thenReturn(Optional.of(new CartStore.Mutation(3)));
        when(catalog.find(List.of(sku))).thenThrow(new CartFailure(503,"CATALOG_UNAVAILABLE","Unavailable"));
        assertThat(service.put(key,CartService.cartId(actor),product,sku,2,2).version()).isEqualTo(3);
        verify(store,times(2)).replay(key);verify(store,never()).put(any(),any(),any(),anyInt(),anyLong());
    }
    @Test void concurrentOriginalCommitIsReplayedWhenOfferWasHiddenDuringLookup(){
        when(store.replay(key)).thenReturn(Optional.empty()).thenReturn(Optional.of(new CartStore.Mutation(1)));
        when(catalog.find(List.of(sku))).thenReturn(Map.of());
        assertThat(service.put(key,CartService.cartId(actor),product,sku,2,0).version()).isEqualTo(1);
        verify(store,never()).put(any(),any(),any(),anyInt(),anyLong());
    }
    @Test void oldAccountCartIdFailsBeforeLookupOrReplay(){
        assertThatThrownBy(()->service.put(key,CartService.cartId(UUID.randomUUID()),product,sku,2,0)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
        verifyNoInteractions(store,catalog);
    }
}
