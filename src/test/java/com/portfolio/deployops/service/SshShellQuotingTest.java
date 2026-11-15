package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SshShellQuotingTest {
    @Test void preservesEmptyAndSpecialArgumentsThroughBothShellBoundaries() throws Exception {
        var values=List.of("", "false", "space arg", "quote'arg", "double\"arg", "dollar$arg", "semi;arg", "한글", "TLSv1.2 TLSv1.3", "back\\slash", "line\nbreak", "*?[abc]",
            "$(printf SHOULD_NOT_EXPAND)", "`printf SHOULD_NOT_EXPAND`", "mixed'\"\\value");
        var args=new ArrayList<>(List.of("printf","<%s>\\n"));args.addAll(values);
        String inner=args.stream().map(SshCommandService::shellQuote).reduce((a,b)->a+" "+b).orElseThrow();
        String remote="bash -lc "+SshCommandService.shellQuote(inner);
        assertThat(remote).doesNotContain("\"");
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String bash=windows?Path.of(System.getenv().getOrDefault("ProgramFiles","C:/Program Files"),"Git","bin","bash.exe").toString():"/bin/bash";
        var builder=new ProcessBuilder(bash,"-lc",remote);builder.environment().put("LANG","C.UTF-8");
        Process process=builder.start();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();
        String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        String stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(stderr).isZero();
        assertThat(stdout).isEqualTo(values.stream().map(v->"<"+v+">\n").reduce("",String::concat));
    }
    @Test void encodedTransportContainsNoLiteralDoubleQuotes() {
        for(String value:List.of("$(id)","`id`","a;b","'\"$HOME", "")) {
            assertThat(SshCommandService.shellQuote(value)).doesNotContain("\"");
        }
        assertThat(SshCommandService.shellQuote(null)).isEqualTo("''");
    }
}
