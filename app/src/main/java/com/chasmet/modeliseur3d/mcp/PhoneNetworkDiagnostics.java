package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;

/** Observations only: a routable address does not prove an open firewall or an IPv4 mapping. */
public final class PhoneNetworkDiagnostics {
    private PhoneNetworkDiagnostics() { }

    public static String classify(InetAddress address) {
        if (address.isAnyLocalAddress()) return "unspecified";
        if (address.isLoopbackAddress()) return "loopback";
        if (address.isMulticastAddress()) return "multicast";
        if (address.isLinkLocalAddress()) return "link_local";
        byte[] b=address.getAddress();
        if (address instanceof Inet6Address) {
            if ((b[0]&254)==252) return "ipv6_ula";
            // IPv6 global unicast 2000::/3, excluding documentation space.
            if ((b[0]&224)==32 && !((b[0]&255)==32 && (b[1]&255)==1 && (b[2]&255)==13 && (b[3]&255)==184)) return "ipv6_global";
            return "ipv6_special";
        }
        if (!(address instanceof Inet4Address)) return "unknown";
        int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        if (a==100 && c>=64 && c<=127) return "ipv4_cgnat";
        if (address.isSiteLocalAddress()) return "ipv4_private";
        if (a==0 || a==127 || a>=224 || (a==192 && c==0 && (d==0 || d==2))
                || (a==198 && (c==18 || c==19 || (c==51 && d==100)))
                || (a==203 && c==0 && d==113) || (a==192 && c==88 && d==99)) return "ipv4_special";
        return "ipv4_public";
    }

    public static boolean global(InetAddress address) {
        String kind=classify(address);
        return "ipv4_public".equals(kind) || "ipv6_global".equals(kind);
    }

    public static JSONObject snapshot(Context context) throws Exception {
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONArray addresses=new JSONArray();
        boolean internet=false,validated=false,v6=false,v4=false,private4=false,cgnat=false;
        String type="offline";
        // The active link avoids selecting an obsolete Wi-Fi/mobile address after a handover.
        android.net.NetworkInfo active=manager==null?null:manager.getActiveNetworkInfo();
        Network[] networks=manager==null?new Network[0]:manager.getAllNetworks();
        for (Network network:networks) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            LinkProperties properties=manager.getLinkProperties(network);
            if (caps==null || properties==null) continue;
            if (android.os.Build.VERSION.SDK_INT>=23) {
                if (!network.equals(manager.getActiveNetwork())) continue;
            } else if (active==null || !active.isConnected() || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            internet=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            validated=android.os.Build.VERSION.SDK_INT>=23 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            type=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"wifi":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"vpn":"other";
            for (LinkAddress link:properties.getLinkAddresses()) {
                InetAddress ip=link.getAddress();String kind=classify(ip);
                addresses.put(new JSONObject().put("address",ip.getHostAddress()).put("kind",kind));
                v6|="ipv6_global".equals(kind);v4|="ipv4_public".equals(kind);
                private4|="ipv4_private".equals(kind);cgnat|="ipv4_cgnat".equals(kind);
            }
        }
        return new JSONObject().put("network",type).put("internet_capability",internet).put("internet_validated",validated)
                .put("addresses",addresses).put("global_ipv6_present",v6).put("public_ipv4_on_phone",v4)
                .put("private_ipv4_present",private4).put("cgnat_on_link",cgnat)
                .put("upstream_nat",private4?"unknown_requires_box_wan_address":"not_determined")
                .put("double_nat","not_determined").put("inbound_access","not_tested")
                .put("preferred_method",v6?"ipv6_direct":v4?"ipv4_direct":private4?"ipv4_box_mapping_required":"unavailable");
    }

    public static String summary(JSONObject state) {
        StringBuilder text=new StringBuilder("Réseau : ").append(state.optString("network","inconnu"));
        JSONArray addresses=state.optJSONArray("addresses");
        if (addresses!=null) for (int i=0;i<addresses.length();i++) {
            JSONObject address=addresses.optJSONObject(i);
            if (address!=null) text.append("\n").append(address.optString("address")).append(" · ").append(address.optString("kind"));
        }
        if (state.optBoolean("global_ipv6_present")) text.append("\nIPv6 globale détectée. Le pare-feu entrant reste à vérifier.");
        else if (state.optBoolean("private_ipv4_present")) text.append("\nIPv4 privée : adresse WAN et redirection de la box à vérifier. NAT, double NAT et CGNAT amont indéterminés.");
        else if (state.optBoolean("cgnat_on_link")) text.append("\nAdresse CGNAT : accès IPv4 entrant direct indisponible.");
        else text.append("\nAucune adresse directe exploitable observée.");
        return text.toString();
    }
}
