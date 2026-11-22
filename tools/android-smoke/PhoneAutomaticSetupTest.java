package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PhoneAutomaticSetupTest {
    private Context app;
    private SharedPreferences phone,background;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();phone=PhoneMcpSettings.prefs(app);
        background=app.getSharedPreferences("mcp_background",0);
        phone.edit().clear().commit();background.edit().clear().putBoolean("enabled",true).commit();
        PhoneMcpSettings.certificate(app).delete();
    }
    @After public void cleanup() {phone.edit().clear().commit();background.edit().clear().commit();}
    @Test public void manualRetryImmediatelyReleasesTheActiveManagersLocalBackoff() throws Exception {
        long due=System.currentTimeMillis()+3600000;
        phone.edit().putLong("acme_retry_at",due).commit();
        try(PhonePublicConnection manager=new PhonePublicConnection(app,null)) {
            assertEquals(due,PhonePublicConnection.nextAttemptAt(app));
            McpConnectionService.renewCertificate(app);
            assertEquals(0,PhonePublicConnection.nextAttemptAt(app));
            assertTrue(phone.getBoolean("automatic_force_renew",false));
            // Simulate a failed new attempt: a repeated tap must retain the new delay.
            phone.edit().putLong("acme_retry_at",due).commit();McpConnectionService.renewCertificate(app);
            assertEquals(due,PhonePublicConnection.nextAttemptAt(app));
        }
    }
    @Test public void manualRetryCannotBypassTheCertificateAuthoritysRetryAfter() {
        long mandatory=System.currentTimeMillis()+7200000;
        phone.edit().putLong("acme_retry_at",mandatory).putLong("acme_server_retry_at",mandatory).commit();
        McpConnectionService.renewCertificate(app);
        assertEquals(mandatory,PhonePublicConnection.nextAttemptAt(app));
    }
    @Test public void updateAutomaticallyRetriesTheOldBadCsrOnlyOnce() {
        phone.edit().putBoolean("direct",true).putBoolean("automatic_https",true)
                .putString("automatic_last_error","Validation ACME refusée : badCSR").putLong("acme_retry_at",Long.MAX_VALUE).commit();
        PhoneMcpSettings.recoverSetupAfterUpdate(app);
        assertEquals(0,PhonePublicConnection.nextAttemptAt(app));assertTrue(phone.getBoolean("automatic_force_renew",false));
        phone.edit().putLong("acme_retry_at",Long.MAX_VALUE).commit();PhoneMcpSettings.recoverSetupAfterUpdate(app);
        assertEquals(Long.MAX_VALUE,PhonePublicConnection.nextAttemptAt(app));
    }
    @Test public void restoresFailedAutomaticSetupAfterTheBlankManualConfiguration() {
        phone.edit().putBoolean("direct",false).putBoolean("automatic_https",false)
                .putString("automatic_last_error","Validation ACME refusée : badCSR").commit();
        assertTrue(PhoneMcpSettings.recoverSetupAfterUpdate(app));
        assertTrue(PhoneMcpSettings.direct(app));assertTrue(phone.getBoolean("automatic_https",false));
    }
    @Test public void waitsForSavedCommandBeforeChangingItsTransport() {
        phone.edit().putString("automatic_last_error","badCSR").commit();
        background.edit().putString("command","saved-command").commit();
        assertFalse(PhoneMcpSettings.recoverSetupAfterUpdate(app));assertFalse(PhoneMcpSettings.direct(app));
        background.edit().remove("command").commit();assertTrue(PhoneMcpSettings.recoverSetupAfterUpdate(app));
    }
    @Test public void preservesDisabledMcpManualAddressAndOrdinaryRelayChoice() {
        phone.edit().putString("automatic_last_error","badCSR").commit();background.edit().putBoolean("enabled",false).commit();
        assertFalse(PhoneMcpSettings.recoverSetupAfterUpdate(app));assertFalse(PhoneMcpSettings.direct(app));assertFalse(McpConnectionService.enabled(app));
        phone.edit().clear().putString("automatic_last_error","badCSR").putString("public_base","https://manual.example:8443").commit();background.edit().putBoolean("enabled",true).commit();
        assertFalse(PhoneMcpSettings.recoverSetupAfterUpdate(app));assertEquals("https://manual.example:8443",PhoneMcpSettings.publicBase(app));
        phone.edit().clear().putBoolean("direct",false).commit();assertFalse(PhoneMcpSettings.recoverSetupAfterUpdate(app));assertFalse(PhoneMcpSettings.direct(app));
    }
}
