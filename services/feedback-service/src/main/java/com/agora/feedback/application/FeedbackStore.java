package com.agora.feedback.application;
import com.agora.feedback.domain.Feedback.*;
import java.util.UUID;
public interface FeedbackStore {
    Created create(Line line,Content content);
    Review owned(UUID buyer,UUID order,UUID sku);
    Review edit(UUID buyer,UUID id,long version,Content content);
    Review reply(UUID shop,UUID id,long version,String text);
    Review moderate(UUID actor,UUID id,long version,boolean hidden,String reason);
    Page list(UUID product,String cursor);
    Summary summary(UUID product);
    ManagedReview managed(UUID shop,UUID id);
    ManagedPage managedList(UUID shop,String cursor);
}
