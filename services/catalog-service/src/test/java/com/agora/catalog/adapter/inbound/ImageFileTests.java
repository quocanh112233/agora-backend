package com.agora.catalog.adapter.inbound;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import static org.assertj.core.api.Assertions.*;

class ImageFileTests {
    private static byte[] image(String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }
    private static MockPart part(byte[] bytes, String mime) {
        var part = new MockPart("file", "image", bytes);
        part.getHeaders().setContentType(MediaType.parseMediaType(mime));
        return part;
    }
    private static byte[] withCompressedText(byte[] png) throws IOException {
        byte[] text = new byte[256 * 1024]; Arrays.fill(text, (byte) 'A');
        var compressed = new ByteArrayOutputStream();
        try (var zip = new DeflaterOutputStream(compressed)) { zip.write(text); }
        var bytes = new ByteArrayOutputStream();
        bytes.write(png, 0, 33); // Insert ancillary chunks after the fixed-size IHDR.
        var out = new DataOutputStream(bytes);
        for (int i = 0; i < 32; i++) {
            var payload = new ByteArrayOutputStream();
            payload.writeBytes(("text" + i).getBytes(StandardCharsets.ISO_8859_1));
            payload.write(0); payload.write(0); payload.writeBytes(compressed.toByteArray());
            byte[] type = "zTXt".getBytes(StandardCharsets.US_ASCII), data = payload.toByteArray();
            var crc = new CRC32(); crc.update(type); crc.update(data);
            out.writeInt(data.length); out.write(type); out.write(data); out.writeInt((int) crc.getValue());
        }
        bytes.write(png, 33, png.length - 33);
        return bytes.toByteArray();
    }
    @Test void compressedTextMetadataIsNotInflatedDuringValidation() throws Exception {
        byte[] plain = image("png");
        ImageFile.read(part(plain, "image/png"));
        byte[] data = withCompressedText(plain);
        var upload = part(data, "image/png");
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(allocations.isThreadAllocatedMemorySupported()).isTrue();
        if (!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        long before = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        ImageFile result = ImageFile.read(upload);
        long allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertThat(result.width()).isEqualTo(2); assertThat(result.height()).isEqualTo(3);
        assertThat(result.data()).isEqualTo(data);
        // The file carries 8 MiB of text; validation only needs the bounded file and image header.
        assertThat(allocated).isLessThan(2 * 1024 * 1024);
    }
    @Test void jpegStillProvidesDimensionsWithoutReadingOptionalMetadata() throws Exception {
        ImageFile result = ImageFile.read(part(image("jpeg"), "image/jpeg"));
        assertThat(result.mime()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(2); assertThat(result.height()).isEqualTo(3);
    }
}
