package com.agora.commerce;

import com.agora.commerce.domain.Cart;
import com.agora.commerce.domain.CartFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CartTests {
    final UUID product=UUID.randomUUID(),sku=UUID.randomUUID();
    @Test void emptyCartHasVersionZeroAndAbsolutePutDoesNotAccumulate(){
        var empty=Cart.empty();assertThat(empty.version()).isZero();assertThat(empty.items()).isEmpty();
        var first=empty.put(product,sku,3,0);assertThat(first.version()).isEqualTo(1);assertThat(first.items().getFirst().quantity()).isEqualTo(3);
        var second=first.put(product,sku,5,1);assertThat(second.version()).isEqualTo(2);assertThat(second.items()).hasSize(1);assertThat(second.items().getFirst().quantity()).isEqualTo(5);
        assertThat(first.items().getFirst().quantity()).isEqualTo(3);
    }
    @Test void sameQuantityAndMissingDeleteAreNoOpsButStillCheckVersion(){
        var first=Cart.empty().put(product,sku,3,0);
        assertThat(first.put(product,sku,3,1)).isEqualTo(first);
        assertThat(first.remove(UUID.randomUUID(),1)).isEqualTo(first);
        assertThatThrownBy(()->first.remove(UUID.randomUUID(),0)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
        var removed=first.remove(sku,1);assertThat(removed.version()).isEqualTo(2);assertThat(removed.items()).isEmpty();
    }
    @Test void keepsTwentyDistinctSkusInStableOrderWithoutAOneShopLimit(){
        Cart cart=Cart.empty();for(int i=0;i<20;i++)cart=cart.put(UUID.randomUUID(),new UUID(0,20-i),99,i);
        assertThat(cart.items()).hasSize(20);assertThat(cart.items().getFirst().skuId()).isEqualTo(new UUID(0,1));
        Cart full=cart;assertThatThrownBy(()->full.put(product,sku,1,20)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
        assertThat(cart.put(cart.items().getFirst().productId(),cart.items().getFirst().skuId(),1,20).items()).hasSize(20);
    }
    @Test void rejectsInvalidQuantityAndStaleVersion(){
        for(int quantity:List.of(-1,0,100,Integer.MAX_VALUE))assertThatThrownBy(()->Cart.empty().put(product,sku,quantity,0)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(422));
        assertThatThrownBy(()->Cart.empty().put(product,sku,1,1)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
    }
    @Test void doesNotMoveSkuToAnotherProductOrOverflowVersion(){
        var first=Cart.empty().put(product,sku,1,0);
        assertThatThrownBy(()->first.put(UUID.randomUUID(),sku,2,1)).isInstanceOf(CartFailure.class);
        var max=new Cart(9007199254740991L,first.items());
        assertThatThrownBy(()->max.put(product,sku,2,max.version())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(()->max.remove(sku,max.version())).isInstanceOf(CartFailure.class);
    }
    @Test void itemListCannotBeMutatedByCaller(){
        var first=Cart.empty().put(product,sku,1,0);
        assertThatThrownBy(()->first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
