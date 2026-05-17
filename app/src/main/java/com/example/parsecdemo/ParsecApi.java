package com.example.parsecdemo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ParsecApi {
    private static final String BASE = "https://kessel-api.parsec.app";
    private static final String USER_AGENT = "parsec/150-93b Windows/11 libmatoya/4.0";

    public static final class ClientInfo {
        public final String sessionId;
        public final int userId;
        public final String hostPeerId;
        public ClientInfo(String s, int u, String h) { sessionId = s; userId = u; hostPeerId = h; }
    }

    public static final class Host {
        public final String peerId;
        public final String name;
        public final String userName;
        public final boolean online;
        public Host(String p, String n, String u, boolean o) { peerId = p; name = n; userName = u; online = o; }
    }

    public static final class AuthResult {
        public final ClientInfo info;
        public final boolean tfaRequired;
        public final String error;
        public AuthResult(ClientInfo i, boolean t, String e) { info = i; tfaRequired = t; error = e; }
    }

    public static AuthResult login(String email, String password, String tfa) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        if (tfa != null && !tfa.isEmpty()) body.put("tfa", tfa);

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/v1/auth").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 201) {
                JSONObject j = new JSONObject(resp);
                ClientInfo info = new ClientInfo(
                        j.getString("session_id"),
                        j.optInt("user_id"),
                        j.optString("host_peer_id", ""));
                return new AuthResult(info, false, null);
            }
            JSONObject j = new JSONObject(resp);
            boolean tfaReq = j.optBoolean("tfa_required", false);
            String err = j.optString("error", "HTTP " + code);
            return new AuthResult(null, tfaReq, err);
        } finally {
            c.disconnect();
        }
    }

    public static List<Host> listHosts(String sessionId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/v2/hosts?mode=desktop&public=false").openConnection();
        try {
            c.setRequestMethod("GET");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + sessionId);
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                throw new RuntimeException("hosts HTTP " + code + ": " + resp);
            }
            JSONObject j = new JSONObject(resp);
            JSONArray arr = j.optJSONArray("data");
            List<Host> out = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject h = arr.getJSONObject(i);
                    String peer = h.optString("peer_id", "");
                    String name = h.optString("name", "(unnamed)");
                    String userName = "";
                    JSONObject u = h.optJSONObject("user");
                    if (u != null) userName = u.optString("name", "");
                    boolean online = h.optBoolean("online", false);
                    out.add(new Host(peer, name, userName, online));
                }
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
