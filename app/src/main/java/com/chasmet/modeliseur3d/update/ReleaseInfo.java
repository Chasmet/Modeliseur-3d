package com.chasmet.modeliseur3d.update;

/** Public release metadata; no credentials. */
public final class ReleaseInfo {
    public final String version, url, sha256, notes;
    public final long bytes;
    public ReleaseInfo(String version, String url, String sha256, String notes, long bytes) {
        this.version = version; this.url = url; this.sha256 = sha256; this.notes = notes; this.bytes = bytes;
    }
    public static boolean newer(String remote, String local) {
        String a=remote.replaceFirst("^[vV]", ""), b=local.replaceFirst("^[vV]", "");
        if (!a.matches("\\d+\\.\\d+\\.\\d+") || !b.matches("\\d+\\.\\d+\\.\\d+")) return false;
        String[] x=a.split("\\."), y=b.split("\\.");
        for(int i=0;i<3;i++) {
            long left=Long.parseLong(x[i]), right=Long.parseLong(y[i]);
            if(left!=right) return left>right;
        }
        return false;
    }
}
