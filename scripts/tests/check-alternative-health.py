#!/usr/bin/env python3
"""Exercise the actual verifier with deterministic VPN-scoped HTTPS outcomes."""
from pathlib import Path
import tempfile, subprocess, shutil

root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/com/rahyab/next/TunnelVerifier.java').read_text()
files = {
'android/content/Context.java': '''package android.content;
public class Context { public static final String CONNECTIVITY_SERVICE="connectivity";
 public Object getSystemService(String name) { return android.net.ConnectivityManager.INSTANCE; } }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock {
 public static long elapsedRealtime(){return System.nanoTime()/1000000;} }''',
'android/net/NetworkCapabilities.java': '''package android.net; public class NetworkCapabilities {
 public static final int TRANSPORT_VPN=4; public boolean hasTransport(int t){return true;} }''',
'android/net/ConnectivityManager.java': '''package android.net; public class ConnectivityManager {
 public static final ConnectivityManager INSTANCE=new ConnectivityManager(); public Network active;
 public Network getActiveNetwork(){return active;} public NetworkCapabilities getNetworkCapabilities(Network n){return new NetworkCapabilities();} }''',
'android/net/Network.java': '''package android.net;
import java.net.*;import java.io.*;
public class Network { public int mode,calls; public Network(int m){mode=m;}
 public URLConnection openConnection(URL url, Proxy proxy) throws IOException {
  if(proxy!=Proxy.NO_PROXY)throw new AssertionError("Unexpected HTTP proxy"); calls++;
  boolean literal=url.getHost().equals("1.1.1.1");
  if(mode==3)throw new SocketTimeoutException("simulated outage");
  if((mode==1||mode==4)&&url.getHost().equals("api.ipify.org"))throw new UnknownHostException("one endpoint failed");
  if(mode==2&&!literal)throw new UnknownHostException("simulated DNS failure");
  return new HttpURLConnection(url){public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
   public int getResponseCode(){if(mode==4)ConnectivityManager.INSTANCE.active=new Network(1);return literal||mode==5?200:204;}
   public InputStream getInputStream(){return new ByteArrayInputStream((literal?"ip=198.51.100.8\\n":"{\\"ip\\":\\"198.51.100.8\\"}").getBytes());}
  };
 }
}''',
'org/json/JSONObject.java': '''package org.json; public class JSONObject {
 public JSONObject(String s){} public String optString(String key,String fallback){return "198.51.100.8";} }''',
'com/rahyab/next/TunnelVerifier.java': source,
'com/rahyab/next/HealthTest.java': '''package com.rahyab.next;
import android.net.*;import android.content.*;
public class HealthTest {
 static TunnelVerifier.Result probe(int mode){ConnectivityManager.INSTANCE.active=new Network(mode);return TunnelVerifier.verify(new Context());}
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 public static void main(String[] args){
  check(probe(1).verified,"Failure of ipify must not reject independent HTTPS success");
  check(ConnectivityManager.INSTANCE.active.calls==2,"Fallback did not use captured VPN");
  TunnelVerifier.Result dns=probe(2);
  check(!dns.verified&&dns.dnsOnlyFailure,"Numeric HTTPS success must request DNS repair, never green");
  TunnelVerifier.Result down=probe(3);
  check(!down.verified&&!down.dnsOnlyFailure,"Transport outage misclassified");
  check(!probe(4).verified,"Network replacement falsely verified");
  check(probe(5).verified,"Valid IP HTTPS response rejected");
  Thread.currentThread().interrupt();check(!probe(1).verified,"Cancelled probe verified");Thread.interrupted();
  System.out.println("PASS: independent HTTPS fallback, DNS-only degradation, transport outage, network replacement, cancellation.");
 }
}'''
}
with tempfile.TemporaryDirectory() as temp:
    folder=Path(temp)
    for name, data in files.items():
        path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    inputs=[str(folder/n) for n in files]
    javac=shutil.which('javac')
    if javac: subprocess.run([javac,'-d',str(folder),*inputs],check=True)
    else:
        launcher=folder/'Compile.java'
        launcher.write_text('class Compile { public static void main(String[] a){System.exit(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,a));}}')
        subprocess.run(['java',str(launcher),'-d',str(folder),*inputs],check=True)
    subprocess.run(['java','-cp',str(folder),'com.rahyab.next.HealthTest'],check=True)
