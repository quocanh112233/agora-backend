package com.agora.feedback.domain;

import java.time.Instant;
import java.util.*;
public final class Feedback {
    private Feedback() {}
    public record Actor(UUID id,boolean admin) {}
    public record Line(UUID buyerId,UUID orderId,UUID skuId,UUID productId,UUID shopId) {}
    public record Content(int rating,String content) {
        public Content {if(rating<1||rating>5)throw invalid();content=text(content,2000);}
    }
    public record Review(UUID id,UUID buyerId,UUID orderId,UUID skuId,UUID productId,UUID shopId,int rating,String content,String reply,boolean hidden,long version,Instant createdAt,Instant updatedAt) {
        public ManagedReview managedView(){return new ManagedReview(id,productId,shopId,rating,content,reply,hidden,version,createdAt,updatedAt);}
        public PublicReview publicView(){return new PublicReview(id,productId,shopId,rating,content,reply,version,createdAt,updatedAt);}
    }
    public record PublicReview(UUID id,UUID productId,UUID shopId,int rating,String content,String reply,long version,Instant createdAt,Instant updatedAt) {}
    public record ManagedReview(UUID id,UUID productId,UUID shopId,int rating,String content,String reply,boolean hidden,long version,Instant createdAt,Instant updatedAt) {}
    public record ManagedPage(List<ManagedReview> items,String nextCursor) {}
    public record Created(Review review,boolean created) {}
    public record Summary(long count,Double averageRating) {}
    public record Page(List<PublicReview> items,String nextCursor) {}
    public static String text(String value,int max){if(value==null)throw invalid();value=value.strip();if(value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw invalid();return value;}
    public static void version(long value){if(value<0||value>=9007199254740991L)throw invalid();}
    public static FeedbackFailure invalid(){return new FeedbackFailure(422,"VALIDATION_ERROR","Provide only documented fields within their limits.");}
    public static FeedbackFailure missing(){return new FeedbackFailure(404,"NOT_FOUND","Review was not found.");}
    public static FeedbackFailure conflict(){return new FeedbackFailure(409,"REVIEW_CONFLICT","Review has changed or this order line was already reviewed.");}
    public static FeedbackFailure unavailable(String dependency){return new FeedbackFailure(503,dependency+"_UNAVAILABLE","Review information is temporarily unavailable.");}
}
