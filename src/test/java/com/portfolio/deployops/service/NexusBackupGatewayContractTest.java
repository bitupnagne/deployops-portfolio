package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NexusBackupGatewayContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode result(){var n=mapper.createObjectNode();n.put("schema",1);n.put("requestId",1);n.put("status","COMPLETED");n.put("stage","DONE");n.put("originalStateRestored",true);n.put("artifactId","nexus-backup-1");n.put("manifestSha256","a".repeat(64));n.put("completedAt","2026-10-11T01:00:00Z");return n;}
    @Test void completeEvidenceAccepted()throws Exception{assertThat(NexusBackupJobGateway.parseResult(result(),1).status()).isEqualTo("COMPLETED");}
    @Test void missingRestoreOrManifestOrArtifactRejects(){for(String key:List.of("originalStateRestored","manifestSha256","artifactId","completedAt")){var n=result();n.remove(key);assertThatThrownBy(()->NexusBackupJobGateway.parseResult(n,1)).isInstanceOf(java.io.IOException.class);}}
    @Test void differentRequestAndRawFieldsReject(){var n=result();n.put("requestId",2);assertThatThrownBy(()->NexusBackupJobGateway.parseResult(n,1)).isInstanceOf(java.io.IOException.class);n.put("requestId",1);n.put("rawLog","secret");assertThatThrownBy(()->NexusBackupJobGateway.parseResult(n,1)).hasMessageContaining("UNSAFE");}
    @Test void failedWithoutOriginalRestoreBecomesRecoveryRequired()throws Exception{var n=result();n.put("status","FAILED");n.put("errorCode","BACKUP_FAILED");n.put("originalStateRestored",false);assertThat(NexusBackupJobGateway.parseResult(n,1).status()).isEqualTo("RECOVERY_REQUIRED");}
    @Test void restoredFailureRemainsFailure()throws Exception{var n=result();n.put("status","FAILED");n.put("errorCode","BACKUP_FAILED");assertThat(NexusBackupJobGateway.parseResult(n,1).originalStateRestored()).isTrue();}
    @Test void schemaMustBeNumberObjectAndErrorSafe(){var n=result();n.put("schema","1");assertThatThrownBy(()->NexusBackupJobGateway.parseResult(n,1)).isInstanceOf(java.io.IOException.class);assertThatThrownBy(()->NexusBackupJobGateway.parseResult(mapper.createArrayNode(),1)).hasMessageContaining("OBJECT");n.put("schema",1);n.put("errorCode","/private/path");assertThatThrownBy(()->NexusBackupJobGateway.parseResult(n,1)).hasMessageContaining("ERROR_CODE");}
    @Test void duplicateFieldsAndTrailingValuesRejected(){assertThatThrownBy(()->NexusBackupJobGateway.decode("{\"schema\":1,\"schema\":2}".getBytes(StandardCharsets.UTF_8),mapper)).isInstanceOf(java.io.IOException.class);assertThatThrownBy(()->NexusBackupJobGateway.decode("{} {}".getBytes(StandardCharsets.UTF_8),mapper)).hasMessageContaining("MULTIPLE");}
    @Test void brokerAbsentFailsClosed(){assertThat(new NexusBackupJobGateway("absent-nexus-test-queue").ready()).isFalse();}
}
