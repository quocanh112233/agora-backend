package com.agora.catalog.adapter.inbound;

import com.agora.catalog.domain.CatalogFailure;
import jakarta.servlet.http.Part;
import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

record ImageFile(byte[] data, String mime, int width, int height) {
    static ImageFile read(Part part) {
        if (part.getSize() > 5242880) throw new CatalogFailure(413, "FILE_TOO_LARGE", "Image must be at most 5 MiB.");
        if (part.getSize() == 0) throw ProductInput.invalid("Image must not be empty.");
        String mime = part.getContentType();
        if (!"image/png".equals(mime) && !"image/jpeg".equals(mime)) throw unsupported();
        try (var in = part.getInputStream()) {
            byte[] data = in.readNBytes(5242881);
            if (data.length > 5242880) throw new CatalogFailure(413, "FILE_TOO_LARGE", "Image must be at most 5 MiB.");
            boolean png = data.length >= 8 && Arrays.equals(Arrays.copyOf(data, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
            boolean jpg = data.length >= 3 && data[0] == (byte)255 && data[1] == (byte)216 && data[2] == (byte)255;
            if (!(mime.equals("image/png") ? png : jpg)) throw unsupported();
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw unsupported();
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long)width * height > 20000000)
                        throw ProductInput.invalid("Image must contain at most 20 million pixels.");
                    return new ImageFile(data, mime, width, height);
                } finally { reader.dispose(); }
            }
        } catch (IOException e) { throw ProductInput.invalid("Image data is invalid."); }
    }
    private static CatalogFailure unsupported() { return new CatalogFailure(415, "UNSUPPORTED_IMAGE", "Provide a JPEG or PNG with matching content type."); }
}
