package com.lightchase.data;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lightchase.contracts.*;
import java.io.*;
import java.security.*;
public final class DataFiles {
 public final MapDefinition map;
 public final RuleConfig rules;
 public final String mapHash, configHash;
 private DataFiles(byte[] mapBytes, byte[] configBytes) {
  JsonObject mapJson=StrictJson.parseObject(mapBytes,"map");
  JsonObject rulesJson=StrictJson.parseObject(configBytes,"rules");
  GameDataValidator.validateShape(mapJson,rulesJson);
  Gson gson=new Gson();
  MapDefinition parsedMap;
  RuleConfig parsedRules;
  try {
   parsedMap=gson.fromJson(mapJson,MapDefinition.class);
   parsedRules=gson.fromJson(rulesJson,RuleConfig.class);
  } catch (JsonParseException e) {
   throw new IllegalArgumentException("map/rules: could not read structure: "+e.getMessage(),e);
  }
  map=parsedMap; rules=parsedRules;
  GameDataValidator.validate(map,rules);
  mapHash=hash(mapBytes); configHash=hash(configBytes);
 }
 public static DataFiles read(byte[] mapBytes, byte[] configBytes) { return new DataFiles(mapBytes,configBytes); }
 public static DataFiles bundled() {
  return read(resource("/maps/test-room.json"),resource("/rules/v1.json"));
 }
 /** Formal 24x18 map. Switch server/client together during the A6/B/C handoff. */
 public static DataFiles bundledFormal() {
  return read(resource("/maps/v1.json"),resource("/rules/v1.json"));
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
