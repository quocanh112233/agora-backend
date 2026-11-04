package com.agora.catalog.adapter.inbound;
import com.agora.catalog.domain.CatalogFailure;
import jakarta.servlet.http.Part;
record ImageFile(byte[] data,String mime,int width,int height) {
 static ImageFile read(Part part) {
  try { var image=com.agora.media.ImageUpload.read(part);return new ImageFile(image.data(),image.mime(),image.width(),image.height()); }
  catch(com.agora.media.MediaFailure failure){throw new CatalogFailure(failure.status(),failure.code(),failure.getMessage());}
 }
}
