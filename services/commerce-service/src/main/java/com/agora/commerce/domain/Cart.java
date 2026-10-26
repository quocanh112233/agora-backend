package com.agora.commerce.domain;

import java.util.*;

public record Cart(long version,List<Item> items) {
    public record Item(UUID productId,UUID skuId,int quantity) {}
    public Cart{items=List.copyOf(items);}
    public static Cart empty(){return new Cart(0,List.of());}
    public Cart put(UUID product,UUID sku,int quantity,long expectedVersion){
        checkVersion(expectedVersion);
        if(quantity<1||quantity>99)throw new CartFailure(422,"VALIDATION_ERROR","Quantity must be between 1 and 99 packages.");
        var previous=items.stream().filter(i->i.skuId().equals(sku)).findFirst();
        if(previous.isPresent()){
            if(!previous.get().productId().equals(product))throw new CartFailure(409,"CART_CHANGED","Cart product reference has changed. Reload the cart.");
            if(previous.get().quantity()==quantity)return this;
        }else if(items.size()>=20)throw new CartFailure(409,"CART_LIMIT","Cart can contain at most 20 distinct packages.");
        var changed=new ArrayList<>(items);changed.removeIf(i->i.skuId().equals(sku));changed.add(new Item(product,sku,quantity));
        return changed(changed);
    }
    public Cart remove(UUID sku,long expectedVersion){
        checkVersion(expectedVersion);
        var changed=items.stream().filter(i->!i.skuId().equals(sku)).toList();
        return changed.size()==items.size()?this:changed(changed);
    }
    private void checkVersion(long expected){if(expected!=version)throw new CartFailure(409,"CART_VERSION_CONFLICT","Cart has changed. Reload it before continuing.");}
    private Cart changed(List<Item> changed){
        if(version>=9007199254740991L)throw new CartFailure(409,"CART_VERSION_CONFLICT","Cart cannot be changed at this version.");
        return new Cart(version+1,changed.stream().sorted(Comparator.comparing(i->i.skuId().toString())).toList());
    }
}
