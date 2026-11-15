package com.portfolio.deployops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NexusRestoreGatewayContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    ObjectNode ready() {
        ObjectNode n = mapper.createObjectNode();
        n.put("schema", 1);
        n.put("requestId", 1);
        n.put("backupRequestId", 2);
        n.put("manifestSha256", "a".repeat(64));
        n.put("status", "HEALTH_READY");
        n.put("stage", "DONE");
        n.putNull("errorCode");
        n.put("cloneContainerId", "b".repeat(64));
        n.put("cloneImageId", "sha256:" + "c".repeat(64));
        n.put("cloneName", "nexus-restore-1");
        n.put("cloneVolume", "nexus-restore-data-1");
        n.put("cloneNetwork", "nexus-restore-net-1");
        n.put("originalProjectionPreserved", true);
        n.put("mutationUncertain", false);
        n.putArray("expectedArtifacts");
        var access=n.putObject("cloneAccess");
        access.put("schema",1);
        access.put("executionHost","deploy01");
        access.put("requiresVerifiedRelay",true);
        var port=access.putArray("ports").addObject();
        port.put("containerPort",8081);port.put("hostIp","127.0.0.1");port.put("hostPort",18003);
        n.put("completedAt", "2026-10-11T01:00:00Z");
        return n;
    }

    @Test void legacyEmptyExpectationsAreHealthOnly() throws Exception {
        var result = NexusRestoreJobGateway.parse(ready(), 1);
        assertThat(result.status()).isEqualTo("HEALTH_READY");
        assertThat(result.expectedArtifacts()).isEqualTo("[]");
    }

    @Test void everyRequiredFieldAndUnsafeFieldRejects() {
        for (String key : List.of("schema", "requestId", "backupRequestId", "manifestSha256",
                "status", "stage", "errorCode", "cloneContainerId", "cloneImageId", "cloneName",
                "cloneVolume", "cloneNetwork", "originalProjectionPreserved", "mutationUncertain",
                "expectedArtifacts", "cloneAccess", "completedAt")) {
            var n = ready();
            n.remove(key);
            assertThatThrownBy(() -> NexusRestoreJobGateway.parse(n, 1)).isInstanceOf(IOException.class);
        }
        var n = ready();
        n.put("keySha256", "PRIVATE_SENTINEL");
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(n, 1)).isInstanceOf(IOException.class);
    }

    @Test void typedBooleanAndIdentityProofAreRequired() {
        var n = ready();
        n.put("mutationUncertain", 0);
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(n, 1)).isInstanceOf(IOException.class);
        ObjectNode wrong = ready();
        wrong.put("cloneName", "worksystem-nexus");
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(wrong, 1)).isInstanceOf(IOException.class);
        var uncertain = ready();
        uncertain.put("mutationUncertain", true);
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(uncertain, 1)).isInstanceOf(IOException.class);
    }

    @Test void expectationsRejectNullMissingFieldsDuplicatesAndWrongShapes() throws Exception {
        for (String raw : List.of("null", "{}", "[null]", "[{}]",
                "[{\"ref\":\"a\",\"sha256\":null,\"baselineReceiptSha256\":\"" + "d".repeat(64) + "\"}]")) {
            assertThatThrownBy(() -> NexusRestoreJobGateway.validateExpectations(mapper.readTree(raw)))
                .isInstanceOf(IOException.class);
        }
        var checks = mapper.createArrayNode();
        var c = checks.addObject();
        c.put("ref", "existing-maven");
        c.put("sha256", "e".repeat(64));
        c.put("baselineReceiptSha256", "f".repeat(64));
        assertThat(NexusRestoreJobGateway.validateExpectations(checks).size()).isEqualTo(1);
        checks.add(c.deepCopy());
        assertThatThrownBy(() -> NexusRestoreJobGateway.validateExpectations(checks)).isInstanceOf(IOException.class);
    }

    @Test void accessCannotExposeGeneralBindingOrBrowserInventedUrl() {
        var n = ready();
        ((ObjectNode)n.path("cloneAccess").path("ports").get(0)).put("hostIp", "0.0.0.0");
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(n, 1)).hasMessageContaining("BIND");
        var unsafe = ready();
        ((ObjectNode)unsafe.path("cloneAccess")).put("url", "https://untrusted.invalid/private");
        assertThatThrownBy(() -> NexusRestoreJobGateway.parse(unsafe, 1)).hasMessageContaining("SCOPE");
    }
    @Test void readyPublicationIoFailureIsAmbiguousNotPrewriteRejection() throws Exception {
        var files = org.mockito.Mockito.mock(NexusBackupJobGateway.class);
        var ready = mapper.createObjectNode().put("schema", 1).put("restoreReady", true);
        org.mockito.Mockito.when(files.readRootFile(org.mockito.ArgumentMatchers.any())).thenReturn(ready);
        org.mockito.Mockito.doThrow(new IOException("SAFE_PARTIAL_PUBLISH")).when(files)
                .publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        var gateway = new NexusRestoreJobGateway(files, "/unused-safe-test-base");
        assertThatThrownBy(() -> gateway.submit(1, 2, "a".repeat(64)))
                .isInstanceOf(IOException.class).isNotInstanceOf(NexusRestoreJobGateway.PublicationNotStarted.class);
        org.mockito.Mockito.verify(files).publish(org.mockito.ArgumentMatchers.eq("restore-1.json"), org.mockito.ArgumentMatchers.any());
    }

    @Test void priorSpoolEvidenceCannotUseDefiniteNoPublicationPath() throws Exception {
        var files = org.mockito.Mockito.mock(NexusBackupJobGateway.class);
        var ready = mapper.createObjectNode().put("schema", 1).put("restoreReady", false);
        org.mockito.Mockito.when(files.readRootFile(org.mockito.ArgumentMatchers.any())).thenReturn(ready);
        var base = java.nio.file.Files.createTempDirectory("restore-existing-spool");
        var inbox = java.nio.file.Files.createDirectory(base.resolve("inbox"));
        var job = java.nio.file.Files.createFile(inbox.resolve("restore-1.json"));
        org.mockito.Mockito.when(files.exists(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> java.nio.file.Files.exists(call.getArgument(0)));
        try {
            var gateway = new NexusRestoreJobGateway(files, base.toString());
            assertThatThrownBy(() -> gateway.submit(1, 2, "a".repeat(64)))
                    .isInstanceOf(IOException.class).isNotInstanceOf(NexusRestoreJobGateway.PublicationNotStarted.class);
            org.mockito.Mockito.verify(files, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        } finally {
            java.nio.file.Files.delete(job);
            java.nio.file.Files.delete(inbox);
            java.nio.file.Files.delete(base);
        }
    }


    ObjectNode bridgeReady(String ip) {
        var n=ready();
        var a=n.putObject("cloneAccess");
        a.put("schema",2).put("transport","EXECUTION_HOST_BRIDGE_ACCESS")
                .put("executionHost","deploy01").put("cloneContainerId","b".repeat(64))
                .put("networkId","d".repeat(64)).put("containerIpv4",ip).put("containerPort",8081)
                .put("requiresVerifiedRelay",true);
        return n;
    }

    @Test void bridgeAccessAcceptsEachRfc1918RangeAndKeepsEvidence() throws Exception {
        for(String ip:List.of("10.250.0.2","172.16.0.2","172.31.255.254","192.168.1.2")) {
            var parsed=NexusRestoreJobGateway.parse(bridgeReady(ip),1);
            assertThat(parsed.status()).isEqualTo("HEALTH_READY");
            assertThat(mapper.readTree(parsed.cloneAccess()).path("containerIpv4").asText()).isEqualTo(ip);
            assertThat(parsed.expectedArtifacts()).isEqualTo("[]");
        }
    }

    @Test void bridgeAccessRejectsNonCanonicalPrivateIpv4() {
        for(String ip:List.of("010.0.0.2","10.01.0.2","10.0.0.256","10.0.0.-1","10.0.0",
                "10.0.0.2.3","localhost","::1","127.0.0.1","0.0.0.0","169.254.1.2",
                "100.64.0.2","172.15.0.2","172.32.0.2","192.169.0.2","224.0.0.2","240.0.0.2","8.8.8.8")) {
            assertThatThrownBy(()->NexusRestoreJobGateway.parse(bridgeReady(ip),1))
                    .isInstanceOf(IOException.class).hasMessageContaining("IPV4");
        }
    }

    @Test void bridgeAccessRequiresExactTypedFieldsAndResultCloneIdentity() {
        var base=bridgeReady("172.20.0.2");
        for(String key:List.of("schema","transport","executionHost","cloneContainerId","networkId",
                "containerIpv4","containerPort","requiresVerifiedRelay")) {
            var n=base.deepCopy();((ObjectNode)n.path("cloneAccess")).remove(key);
            assertThatThrownBy(()->NexusRestoreJobGateway.parse(n,1)).isInstanceOf(IOException.class);
        }
        for(String key:List.of("ports","url","verifiedAt","hostPort")) {
            var n=base.deepCopy();((ObjectNode)n.path("cloneAccess")).put(key,"unsafe");
            assertThatThrownBy(()->NexusRestoreJobGateway.parse(n,1)).isInstanceOf(IOException.class);
        }
        var wrongCid=base.deepCopy();((ObjectNode)wrongCid.path("cloneAccess")).put("cloneContainerId","e".repeat(64));
        assertThatThrownBy(()->NexusRestoreJobGateway.parse(wrongCid,1)).hasMessageContaining("CID");
        for(String key:List.of("transport","networkId","containerPort","requiresVerifiedRelay")) {
            var n=base.deepCopy();((ObjectNode)n.path("cloneAccess")).put(key,"8081");
            assertThatThrownBy(()->NexusRestoreJobGateway.parse(n,1)).isInstanceOf(IOException.class);
        }
        var uncertain=base.deepCopy();uncertain.put("mutationUncertain",true);
        assertThatThrownBy(()->NexusRestoreJobGateway.parse(uncertain,1)).isInstanceOf(IOException.class);
    }
}
