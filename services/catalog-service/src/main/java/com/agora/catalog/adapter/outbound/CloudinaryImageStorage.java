package com.agora.catalog.adapter.outbound;
import com.agora.catalog.application.ImageStorage;
import com.agora.catalog.domain.CatalogFailure;
import java.net.http.HttpClient;
import tools.jackson.databind.json.JsonMapper;
public final class CloudinaryImageStorage extends com.agora.media.CloudinaryImageStorage implements ImageStorage {
 public CloudinaryImageStorage(HttpClient http, JsonMapper json, String cloud, String key, String secret) {
  super(http,json,cloud,key,secret,"products",()->new CatalogFailure(503,"MEDIA_UNAVAILABLE","Image service is unavailable."));
 }
}
