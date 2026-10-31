package com.agora.feedback.application;
import com.agora.feedback.domain.*;
import com.agora.feedback.domain.Feedback.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
@Service
public class FeedbackService {
    private final FeedbackAccess access;private final FeedbackStore store;
    public FeedbackService(FeedbackAccess access,FeedbackStore store){this.access=access;this.store=store;}
    public Created create(Actor actor,UUID order,UUID sku,Content content){var line=access.eligible(actor.id(),order,sku);return storage(()->store.create(line,content));}
    public Review owned(Actor actor,UUID order,UUID sku){return storage(()->store.owned(actor.id(),order,sku));}
    public Review edit(Actor actor,UUID id,long version,Content content){Feedback.version(version);return storage(()->store.edit(actor.id(),id,version,content));}
    public Review reply(Actor actor,UUID id,long version,String content){Feedback.version(version);String text=Feedback.text(content,2000);UUID shop=access.shop(actor.id());return storage(()->store.reply(shop,id,version,text));}
    public Review moderate(Actor actor,UUID id,long version,boolean hidden,String reason){if(!actor.admin())throw new FeedbackFailure(403,"FORBIDDEN","Administrator role is required.");Feedback.version(version);String text=Feedback.text(reason,500);return storage(()->store.moderate(actor.id(),id,version,hidden,text));}
    public ManagedReview managed(Actor actor,boolean admin,UUID id){UUID shop=managementScope(actor,admin);return storage(()->store.managed(shop,id));}
    public ManagedPage managedList(Actor actor,boolean admin,String cursor){UUID shop=managementScope(actor,admin);return storage(()->store.managedList(shop,cursor));}
    private UUID managementScope(Actor actor,boolean admin){if(!admin)return access.shop(actor.id());if(!actor.admin())throw new FeedbackFailure(403,"FORBIDDEN","Administrator role is required.");return null;}
    public Page list(UUID product,String cursor){return storage(()->store.list(product,cursor));}
    public Summary summary(UUID product){return storage(()->store.summary(product));}
    private <T>T storage(Supplier<T> action){try{return action.get();}catch(DataAccessException|TransactionException e){throw Feedback.unavailable("FEEDBACK");}}
}
