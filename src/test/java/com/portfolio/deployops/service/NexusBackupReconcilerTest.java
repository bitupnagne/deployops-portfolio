package com.portfolio.deployops.service;
import com.portfolio.deployops.domain.NexusBackupRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NexusBackupReconcilerTest {
    @Test void submittedMissingResultNeverReplays()throws Exception{var s=mock(NexusBackupService.class);var g=mock(NexusBackupJobGateway.class);var r=new NexusBackupRequest();r.setId(1L);r.setSubmitted(true);r.setStatus("RECONCILING");when(s.unfinished()).thenReturn(List.of(r));var poll=new NexusBackupReconciler(s,g);poll.poll();poll.poll();verify(g,never()).submit(anyLong());verify(s,never()).markSubmitted(anyLong());}
    @Test void publicationFailureDoesNotRollBackDurableSubmissionOrRetry()throws Exception{var s=mock(NexusBackupService.class);var g=mock(NexusBackupJobGateway.class);var r=new NexusBackupRequest();r.setId(1L);when(s.unfinished()).thenReturn(List.of(r));when(s.markSubmitted(1L)).thenReturn(true,false);doThrow(new java.io.IOException("publication failed")).when(g).submit(1L);var poll=new NexusBackupReconciler(s,g);poll.poll();poll.poll();verify(g,times(1)).submit(1L);verify(s,never()).acceptResult(anyLong(),any());}
}
