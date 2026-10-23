package com.lightchase.data;
import com.google.gson.Gson;
import com.lightchase.contracts.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
public final class DataFiles {
 public final MapDefinition map;
 public final RuleConfig rules;
 public final String mapHash, configHash;
 private DataFiles(byte[] mapBytes, byte[] configBytes) {
  Gson gson=new Gson();
  map=gson.fromJson(new String(mapBytes,StandardCharsets.UTF_8),MapDefinition.class);
  rules=gson.fromJson(new String(configBytes,StandardCharsets.UTF_8),RuleConfig.class);
  if(map==null || rules==null || map.spawns==null || map.walls==null || map.width<1 || rules.tickRate!=30)
   throw new IllegalArgumentException("Invalid skeleton data");
  mapHash=hash(mapBytes); configHash=hash(configBytes);
 }
 public static DataFiles read(byte[] mapBytes, byte[] configBytes) { return new DataFiles(mapBytes,configBytes); }
 public static DataFiles bundled() {
  return read(resource("/maps/test-room.json"),resource("/rules/v1.json"));
 }
 private static byte[] resource(String path) {
  try(InputStream in=DataFiles.class.getResourceAsStream(path)) {
   if(in==null) throw new IllegalStateException("Missing resource: "+path);
   ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096];
   for(int n;(n=in.read(buffer))!=-1;) out.write(buffer,0,n);
   return out.toByteArray();
  } catch(IOException e) { throw new IllegalStateException(e); }
 }
 public static String hash(byte[] bytes) {
  try {
   byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
   StringBuilder out=new StringBuilder();
   for(byte b:digest) out.append(String.format("%02x",b&255));
   return out.toString();
  } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
}
