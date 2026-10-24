package com.agora.catalog;

import com.agora.catalog.adapter.outbound.JdbcMediaStore;
import com.agora.catalog.application.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaCleanupTests {
    MediaStore.Intent intent(String publicId) {
        return new MediaStore.Intent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                publicId, "UPLOADED", "image/png", 70, 1, 1, Instant.EPOCH);
    }
    @Test void aCandidateAttachedBeforeTheClaimUpdateIsNotReturnedForDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var candidate = intent("reserved");
        // A concurrent attachment wins after selection: the guarded update affects zero rows.
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MediaStore.Intent>>any())).thenReturn(List.of(candidate));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        var store = new JdbcMediaStore(jdbc, JsonMapper.builder().build());
        assertThat(store.claimCleanup()).isEmpty();
    }
    @Test void cleanupNeverSendsAnUnreservedPublicIdToStorage() {
        MediaStore store = mock(MediaStore.class);
        var storage = new MediaApiTests.FakeStorage();
        when(store.claimCleanup()).thenReturn(List.of(intent("legacy/do-not-delete")));
        new MediaService(store, storage).cleanup();
        assertThat(storage.deleted).isEmpty();
        verify(store, never()).deleted(any());
    }
}
