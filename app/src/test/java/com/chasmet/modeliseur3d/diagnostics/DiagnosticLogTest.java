package com.chasmet.modeliseur3d.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DiagnosticLogTest {
    @Test public void copyableLogsRemoveConnectionCredentialsAndPrivatePaths(){
        String out=DiagnosticLog.sanitize("Bearer abc-secret https://host/mcp?token=secret password=oops 192.168.1.10 /data/user/0/pkg/private 123456789012345678901234567890abce");
        for(String value:new String[]{"abc-secret","https://host","oops","192.168.1.10","/data/user","123456789012345678901234567890abce"})assertFalse(out.contains(value));
    }
}
