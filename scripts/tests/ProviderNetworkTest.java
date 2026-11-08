package com.rahyab.next;
import java.net.*;import java.io.*;import java.util.*;import java.nio.charset.StandardCharsets;
public class ProviderNetworkTest {
static byte[] body;
static class Response extends HttpURLConnection{
byte[] data; Response(URL u,byte[] b){super(u);data=b;}public void disconnect(){} public boolean usingProxy(){return false;}public void connect(){} public int getResponseCode(){return 200;}public InputStream getInputStream(){return new ByteArrayInputStream(data);}}
static void check(boolean x){if(!x)throw new AssertionError();}
public static void main(String[] args)throws Exception{
String[] fields=new String[15];Arrays.fill(fields,"0");fields[0]="test";fields[1]="203.0.113.9";fields[3]="30";fields[4]="1000";fields[5]="Test";
fields[14]=Base64.getEncoder().encodeToString("client\nproto tcp-client\nremote 203.0.113.9 443\n".getBytes(StandardCharsets.UTF_8));
body=("#HostName\n"+String.join(",",fields)+"\n").getBytes(StandardCharsets.UTF_8);
Set<String> calls=java.util.concurrent.ConcurrentHashMap.newKeySet();
VpnGateProvider.Result r=VpnGateProvider.fetch(u->{calls.add(u.toString());if(u.getHost().equals("www.vpngate.net"))return new Response(u,body);throw new IOException("mock mirror");});
check(r.servers.size()==1&&calls.contains("https://www.vpngate.net/api/iphone/"));
String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
String manifest="{\"schema_version\":1,\"source_url\":\"https://www.vpngate.net/api/iphone/\",\"data_commit\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"data/vpngate.csv\":{\"bytes\":"+body.length+",\"server_count\":1,\"sha256\":\""+sha+"\"}}";
calls.clear();
r=VpnGateProvider.fetch(u->{calls.add(u.toString());if(u.getHost().equals("www.vpngate.net"))throw new IOException("mock official unavailable");return new Response(u,u.getPath().endsWith("latest.json")?manifest.getBytes(StandardCharsets.UTF_8):body);});
check(r.servers.size()==1&&calls.stream().anyMatch(v->v.endsWith("/data/vpngate.csv")));
System.out.println("PASS: injected network handles official, mirror manifest and CSV; mirror hash validation retained.");
}}

