package com.community.residence.notice.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import tools.jackson.databind.*;
import java.util.*;

/** 公告只接受受限JSON文档，不接受任意HTML/CSS、外部图片、脚本或超深节点。 */
public final class NoticeBodyPolicy {
 public record Body(String json,String text,String[] images) { }
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final Set<String> TYPES=Set.of("doc","paragraph","heading","text","hardBreak","bulletList","orderedList","listItem","image");
 private NoticeBodyPolicy() { }
 private static BusinessException invalid(){return new BusinessException(ErrorCode.INVALID_PARAM,"公告正文格式不合法，仅支持正文、二级标题、加粗、列表、对齐及平台上传图片");}
 public static Body parse(String raw) {
  if(raw==null || raw.length()>100000)throw invalid();
  try {
   JsonNode root=JSON.readTree(raw);if(!"doc".equals(root.path("type").asText()))throw invalid();
   var text=new StringBuilder();List<String> images=new ArrayList<>();int[] count={0};
   visit(root,null,0,count,text,images);
   if(text.length()>5000 || images.size()>6 || (text.toString().isBlank()&&images.isEmpty()))throw invalid();
   return new Body(JSON.writeValueAsString(root),text.toString().strip().isBlank()?"[图片公告]":text.toString().strip(),images.toArray(String[]::new));
  }catch(BusinessException e){throw e;}catch(RuntimeException e){throw invalid();}
 }
 private static void keys(JsonNode n,Set<String> allowed) {for(String name:n.propertyNames())if(!allowed.contains(name))throw invalid();}
 private static void visit(JsonNode n,String parent,int depth,int[] count,StringBuilder text,List<String> images) {
  if(!n.isObject() || depth>10 || ++count[0]>1000)throw invalid();
  keys(n,Set.of("type","content","attrs","text","marks"));String type=n.path("type").asText();if(!TYPES.contains(type))throw invalid();
  boolean valid=parent==null?"doc".equals(type):switch(parent) {
   case "doc"->Set.of("paragraph","heading","bulletList","orderedList","image").contains(type);
   case "paragraph","heading"->Set.of("text","hardBreak").contains(type);
   case "bulletList","orderedList"->"listItem".equals(type);
   case "listItem"->Set.of("paragraph","bulletList","orderedList").contains(type);
   default->false;
  };if(!valid)throw invalid();
  if("text".equals(type)) {if(!n.path("text").isString() || n.has("content") || n.has("attrs"))throw invalid();text.append(n.path("text").asText());}
  else if(n.has("text") || n.has("marks"))throw invalid();
  if(n.has("marks")){if(!n.get("marks").isArray())throw invalid();for(var m:n.get("marks")){keys(m,Set.of("type"));if(!"bold".equals(m.path("type").asText()))throw invalid();}}
  JsonNode attrs=n.path("attrs");
  if(n.has("attrs")){
   if(!attrs.isObject())throw invalid();
   if("image".equals(type)) {
    keys(attrs,Set.of("src","alt","widthPercent","textAlign"));String src=attrs.path("src").asText();
    if(!src.matches("/uploads/[A-Za-z0-9_./-]+") || src.contains("..") || src.contains("//"))throw invalid();
    if(attrs.path("alt").asText("").length()>200)throw invalid();
    var width=attrs.path("widthPercent");if(!width.isIntegralNumber() || width.asInt()<25 || width.asInt()>100)throw invalid();images.add(src);
   }else if("heading".equals(type)){keys(attrs,Set.of("level","textAlign"));if(attrs.path("level").asInt()!=2)throw invalid();}
   else if("paragraph".equals(type))keys(attrs,Set.of("textAlign"));
   else if("orderedList".equals(type)){keys(attrs,Set.of("start"));if(attrs.path("start").asInt(1)<1||attrs.path("start").asInt(1)>9999)throw invalid();}
   else throw invalid();
   if(attrs.has("textAlign") && !attrs.get("textAlign").isNull() && !Set.of("left","center","right").contains(attrs.get("textAlign").asText()))throw invalid();
  }else if("image".equals(type)||"heading".equals(type))throw invalid();
  if(n.has("content")){if(!n.get("content").isArray() || Set.of("text","image","hardBreak").contains(type))throw invalid();for(var child:n.get("content"))visit(child,type,depth+1,count,text,images);}
  if(Set.of("paragraph","heading","listItem","hardBreak").contains(type))text.append('\n');
 }
}
