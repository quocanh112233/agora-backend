package com.agora.catalog;

import com.agora.catalog.application.StorefrontQuery;
import com.agora.catalog.domain.CatalogFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StorefrontQueryTests {
    Map<String,String[]> params(String... values){var p=new HashMap<String,String[]>();for(int i=0;i<values.length;i+=2)p.put(values[i],new String[]{values[i+1]});return p;}
    @Test void normalizesOptionalFiltersAndKeepsLiteralSearchText(){
        var q=StorefrontQuery.parse(params("q","  Rau%_\\  ","category","PRODUCE","minPriceVnd","100","maxPriceVnd","200","origin"," Việt Nam "));
        assertThat(q.q()).isEqualTo("Rau%_\\");assertThat(q.origin()).isEqualTo("Việt Nam");
        assertThat(q.minPriceVnd()).isEqualTo(100);assertThat(q.after()).isNull();
        assertThat(StorefrontQuery.parse(params("category","","minPriceVnd","","shopId","")).shopId()).isNull();
    }
    @Test void rejectsUnknownDuplicateInvalidNumbersAndControlCharacters(){
        for(var p:List.of(params("q","x".repeat(101)),params("origin","x".repeat(121)),params("x","1"),params("category","OTHER"),
                params("minPriceVnd","1.5"),params("maxPriceVnd","1000000001"),params("minPriceVnd","200","maxPriceVnd","100"),params("q","a\nb"),params("shopId","bad")))
            assertThatThrownBy(()->StorefrontQuery.parse(p)).isInstanceOf(CatalogFailure.class);
        var duplicated=params();duplicated.put("q",new String[]{"a","b"});assertThatThrownBy(()->StorefrontQuery.parse(duplicated)).isInstanceOf(CatalogFailure.class);
    }
    @Test void cursorRoundTripsAndBindsFilters(){
        var q=StorefrontQuery.parse(params("q","Rau","minPriceVnd","100"));var at=Instant.parse("2026-10-24T10:00:00.123456Z");UUID id=UUID.randomUUID();
        String cursor=q.next(at,id);assertThat(cursor.length()).isLessThanOrEqualTo(256);
        var read=StorefrontQuery.parse(params("q","Rau","minPriceVnd","100","cursor",cursor));
        assertThat(read.after().createdAt()).isEqualTo(at);assertThat(read.after().id()).isEqualTo(id);
        assertThatThrownBy(()->StorefrontQuery.parse(params("q","Fish","cursor",cursor))).isInstanceOf(CatalogFailure.class);
    }
    @Test void rejectsMalformedAndOverlongCursors(){
        for(String value:List.of("","=","a".repeat(257),"aA",Base64.getUrlEncoder().withoutPadding().encodeToString("invalid|uuid|hash".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            assertThatThrownBy(()->StorefrontQuery.parse(params("cursor",value))).isInstanceOf(CatalogFailure.class);
    }
}
