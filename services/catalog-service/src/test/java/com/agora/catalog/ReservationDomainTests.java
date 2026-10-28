package com.agora.catalog;

import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReservationDomainTests {
    final UUID buyer=UUID.randomUUID(),checkout=UUID.randomUUID(),shop=UUID.randomUUID(),product=UUID.randomUUID();
    Request request(List<Line> lines){return new Request(buyer,checkout,shop,lines);}
    @Test void canonicalHashIgnoresInputOrderAndDefensivelyCopies(){
        var a=new Line(product,UUID.fromString("00000000-0000-0000-0000-000000000001"),1);
        var b=new Line(product,UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),99);
        var input=new ArrayList<>(List.of(b,a));var r=request(input);input.clear();
        assertThat(r.lines()).containsExactly(a,b);
        assertThat(r.hash()).matches("[0-9a-f]{64}").isEqualTo(request(List.of(a,b)).hash());
        assertThat(request(List.of(new Line(product,a.skuId(),2),b)).hash()).isNotEqualTo(r.hash());
        assertThat(new Request(buyer,checkout,UUID.randomUUID(),r.lines()).hash()).isNotEqualTo(r.hash());
        assertThatThrownBy(()->r.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsInvalidIdentitiesCountsDuplicatesAndQuantities(){
        var line=new Line(product,UUID.randomUUID(),1);
        for(var lines:List.of(List.<Line>of(),List.of(line,line),java.util.stream.IntStream.range(0,21).mapToObj(i->new Line(product,UUID.randomUUID(),1)).toList()))
            invalid(()->request(lines));
        invalid(()->request(null));invalid(()->request(Arrays.asList(line,null)));
        invalid(()->new Request(null,checkout,shop,List.of(line)));
        invalid(()->new Request(buyer,null,shop,List.of(line)));
        invalid(()->new Request(buyer,checkout,null,List.of(line)));
        invalid(()->request(List.of(new Line(null,line.skuId(),1))));
        invalid(()->request(List.of(new Line(product,null,1))));
        for(int quantity:new int[]{-1,0,100,Integer.MAX_VALUE})invalid(()->request(List.of(new Line(product,line.skuId(),quantity))));
        assertThat(request(java.util.stream.IntStream.range(0,20).mapToObj(i->new Line(product,UUID.randomUUID(),99)).toList()).lines()).hasSize(20);
    }
    void invalid(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogFailure.class,e->assertThat(e.status()).isIn(400,422));}
    @Test void rawHashPreservesFixedM3bCanonicalBytes(){
        var request=new Request(UUID.fromString("11111111-1111-1111-1111-111111111111"),UUID.randomUUID(),UUID.fromString("22222222-2222-2222-2222-222222222222"),List.of(new Line(UUID.fromString("33333333-3333-3333-3333-333333333333"),UUID.fromString("44444444-4444-4444-4444-444444444444"),2)));
        assertThat(request.hash()).isEqualTo("ff8b8e97e7fc515430b16b5ba972d61397a71055ede72717bf0eacfa2231278d");
    }

}
