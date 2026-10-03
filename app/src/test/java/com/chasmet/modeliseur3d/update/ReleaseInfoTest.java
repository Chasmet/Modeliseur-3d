package com.chasmet.modeliseur3d.update;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class ReleaseInfoTest {
    private JSONObject release(String url,String hash) throws Exception {
        return new JSONObject("{\"tag_name\":\"v6.0.1\",\"assets\":[{\"name\":\"modeliseur.apk\",\"size\":123,\"browser_download_url\":\""+url+"\",\"digest\":\""+hash+"\"}]}");
    }
    @Test public void comparesSemanticVersionsWithoutDowngrade() {
        assertTrue(ReleaseInfo.newer("v6.0.1","6.0.0"));
        assertTrue(ReleaseInfo.newer("6.10.0","6.9.99"));
        assertFalse(ReleaseInfo.newer("6.0.1","6.0.1"));
        assertFalse(ReleaseInfo.newer("5.9.10","6.0.0"));
        assertFalse(ReleaseInfo.newer("6.0.1-beta","6.0.0"));
    }
    @Test public void acceptsOnlyVerifiedPublicApkFromThisRepository() throws Exception {
        String url="https://github.com/Chasmet/Modeliseur-3d/releases/download/v6.0.1/modeliseur.apk";
        String hash="sha256:"+"a".repeat(64);
        assertEquals("6.0.1",UpdateManager.parse(release(url,hash),"6.0.0").version);
        assertNull(UpdateManager.parse(release(url,hash),"6.0.1"));
        assertThrows(Exception.class,()->UpdateManager.parse(release("https://evil.example/modeliseur.apk",hash),"6.0.0"));
        assertThrows(Exception.class,()->UpdateManager.parse(release(url,""),"6.0.0"));
        JSONObject beta=release(url,hash).put("prerelease",true);assertNull(UpdateManager.parse(beta,"6.0.0"));
    }
}
